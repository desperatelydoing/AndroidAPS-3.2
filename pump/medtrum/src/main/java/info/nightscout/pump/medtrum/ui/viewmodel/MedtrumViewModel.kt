package info.nightscout.pump.medtrum.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.interfaces.queue.Callback
import info.nightscout.interfaces.queue.CommandQueue
import info.nightscout.pump.medtrum.MedtrumPlugin
import info.nightscout.pump.medtrum.MedtrumPump
import info.nightscout.pump.medtrum.R
import info.nightscout.pump.medtrum.code.ConnectionState
import info.nightscout.pump.medtrum.services.MedtrumService
import info.nightscout.pump.medtrum.code.EventType
import info.nightscout.pump.medtrum.code.PatchStep
import info.nightscout.pump.medtrum.comm.enums.MedtrumPumpState
import info.nightscout.pump.medtrum.encryption.Crypt
import info.nightscout.pump.medtrum.ui.MedtrumBaseNavigator
import info.nightscout.pump.medtrum.ui.event.SingleLiveEvent
import info.nightscout.pump.medtrum.ui.event.UIEvent
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.rx.AapsSchedulers
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventPumpStatusChanged
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.sharedPreferences.SP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

class MedtrumViewModel @Inject constructor(
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val medtrumPlugin: MedtrumPlugin,
    private val commandQueue: CommandQueue,
    val medtrumPump: MedtrumPump,
    private val sp: SP
) : BaseViewModel<MedtrumBaseNavigator>() {

    val patchStep = MutableLiveData<PatchStep>()

    val medtrumService: MedtrumService?
        get() = medtrumPlugin.getService()

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _title = MutableLiveData<Int>(R.string.step_prepare_patch)
    val title: LiveData<Int>
        get() = _title

    private val _eventHandler = SingleLiveEvent<UIEvent<EventType>>()
    val eventHandler: LiveData<UIEvent<EventType>>
        get() = _eventHandler

    private var mInitPatchStep: PatchStep? = null
    private var connectRetryCounter = 0

    init {
        scope.launch {
            medtrumPump.connectionStateFlow.collect { state ->
                aapsLogger.debug(LTag.PUMP, "MedtrumViewModel connectionStateFlow: $state")
                if (patchStep.value != null) {
                    when (state) {
                        ConnectionState.CONNECTED    -> {
                            medtrumPump.lastConnection = System.currentTimeMillis()
                        }

                        ConnectionState.DISCONNECTED -> { // TODO: This is getting ridiciolous, refactor
                            if (patchStep.value != PatchStep.START_DEACTIVATION
                                && patchStep.value != PatchStep.DEACTIVATE
                                && patchStep.value != PatchStep.FORCE_DEACTIVATION
                                && patchStep.value != PatchStep.DEACTIVATION_COMPLETE
                                && patchStep.value != PatchStep.ACTIVATE_COMPLETE
                                && patchStep.value != PatchStep.CANCEL
                                && patchStep.value != PatchStep.ERROR
                                && patchStep.value != PatchStep.PREPARE_PATCH
                                && patchStep.value != PatchStep.PREPARE_PATCH_CONNECT
                            ) {
                                medtrumService?.connect("Try reconnect from viewModel")
                            }
                            if (patchStep.value == PatchStep.PREPARE_PATCH_CONNECT) {
                                // We are disconnected during prepare patch connect, this means we failed to connect (wrong session token?)
                                // Retry 3 times, then give up
                                if (connectRetryCounter < 3) {
                                    connectRetryCounter++
                                    aapsLogger.info(LTag.PUMP, "preparePatchConnect: retry $connectRetryCounter")
                                    medtrumService?.connect("Try reconnect from viewModel")
                                } else {
                                    aapsLogger.info(LTag.PUMP, "preparePatchConnect: failed to connect")
                                    updateSetupStep(SetupStep.ERROR)
                                }
                            }
                        }

                        ConnectionState.CONNECTING   -> {
                        }
                    }
                }
            }
        }
        scope.launch {
            medtrumPump.pumpStateFlow.collect { state ->
                aapsLogger.debug(LTag.PUMP, "MedtrumViewModel pumpStateFlow: $state")
                if (patchStep.value != null) {
                    when (state) {
                        MedtrumPumpState.NONE, MedtrumPumpState.IDLE         -> {
                            updateSetupStep(SetupStep.INITIAL)
                        }

                        MedtrumPumpState.FILLED                              -> {
                            updateSetupStep(SetupStep.FILLED)
                        }

                        MedtrumPumpState.PRIMING                             -> {
                            // updateSetupStep(SetupStep.PRIMING)
                            // TODO: What to do here? start prime counter?
                        }

                        MedtrumPumpState.PRIMED, MedtrumPumpState.EJECTED    -> {
                            updateSetupStep(SetupStep.PRIMED)
                        }

                        MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT -> {
                            updateSetupStep(SetupStep.ACTIVATED)
                        }

                        MedtrumPumpState.STOPPED                             -> {
                            updateSetupStep(SetupStep.STOPPED)
                        }

                        else                                                 -> {
                            updateSetupStep(SetupStep.ERROR)
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    fun moveStep(newPatchStep: PatchStep) {
        val oldPatchStep = patchStep.value

        if (oldPatchStep != newPatchStep) {
            when (newPatchStep) {
                PatchStep.CANCEL                -> {
                    // TODO: Are you sure?
                    // TODO: Dont disconnect when deactivating
                    medtrumService?.disconnect("Cancel")
                }

                PatchStep.COMPLETE              -> {
                    medtrumService?.disconnect("Complete")
                }

                PatchStep.START_DEACTIVATION,
                PatchStep.DEACTIVATE,
                PatchStep.FORCE_DEACTIVATION,
                PatchStep.DEACTIVATION_COMPLETE,
                PatchStep.PREPARE_PATCH         -> {
                    // Do nothing, deactivation uses commandQueue to control connection
                }

                PatchStep.PREPARE_PATCH_CONNECT -> {
                    // Make sure we are disconnected, else dont move step
                    if (medtrumService?.isConnected == true) {
                        // TODO, replace with error message
                        aapsLogger.info(LTag.PUMP, "moveStep: connected, not moving step")
                        return
                    } else {
                    }
                }

                else                            -> {
                    // Make sure we are connected, else dont move step
                    if (medtrumService?.isConnected == false) {
                        aapsLogger.info(LTag.PUMP, "moveStep: not connected, not moving step")
                        return
                    } else {
                    }
                }
            }
        }

        prepareStep(newPatchStep)

        aapsLogger.info(LTag.PUMP, "moveStep: $oldPatchStep -> $newPatchStep")
    }

    fun initializePatchStep(step: PatchStep) {
        mInitPatchStep = prepareStep(step)
    }

    fun preparePatch() {
        medtrumService?.disconnect("PreparePatch")
    }

    fun preparePatchConnect() {
        scope.launch {
            if (medtrumService?.isConnected == false) {
                aapsLogger.info(LTag.PUMP, "preparePatch: new session")
                // New session, generate new session token, only do this when not connected
                medtrumPump.patchSessionToken = Crypt().generateRandomToken()
                // Connect to pump
                medtrumService?.connect("PreparePatch")
            } else {
                aapsLogger.error(LTag.PUMP, "preparePatch: Already connected when trying to prepare patch")
                // Do nothing, we are already connected
            }
        }
    }

    fun startPrime() {
        scope.launch {
            if (medtrumPump.pumpState == MedtrumPumpState.PRIMING) {
                aapsLogger.info(LTag.PUMP, "startPrime: already priming!")
            } else {
                if (medtrumService?.startPrime() == true) {
                    aapsLogger.info(LTag.PUMP, "startPrime: success!")
                } else {
                    aapsLogger.info(LTag.PUMP, "startPrime: failure!")
                    updateSetupStep(SetupStep.ERROR)
                }
            }
        }
    }

    fun startActivate() {
        scope.launch {
            if (medtrumService?.startActivate() == true) {
                aapsLogger.info(LTag.PUMP, "startActivate: success!")
            } else {
                aapsLogger.info(LTag.PUMP, "startActivate: failure!")
                updateSetupStep(SetupStep.ERROR)
            }
        }
    }

    fun deactivatePatch() {
        commandQueue.deactivate(object : Callback() {
            override fun run() {
                if (this.result.success) {
                    // Do nothing, state change will handle this
                } else {
                    updateSetupStep(SetupStep.ERROR)
                }
            }
        })
    }

    fun resetPumpState() {
        medtrumPump.pumpState = MedtrumPumpState.NONE
    }

    private fun prepareStep(newStep: PatchStep): PatchStep {
        val stringResId = when (newStep) {
            PatchStep.PREPARE_PATCH         -> R.string.step_prepare_patch
            PatchStep.PREPARE_PATCH_CONNECT -> R.string.step_prepare_patch_connect
            PatchStep.PRIME                 -> R.string.step_prime
            PatchStep.PRIMING               -> R.string.step_priming
            PatchStep.PRIME_COMPLETE        -> R.string.step_priming_complete
            PatchStep.ATTACH_PATCH          -> R.string.step_attach
            PatchStep.ACTIVATE              -> R.string.step_activate
            PatchStep.ACTIVATE_COMPLETE     -> R.string.step_activate_complete
            PatchStep.START_DEACTIVATION    -> R.string.step_deactivate
            PatchStep.DEACTIVATE            -> R.string.step_deactivating
            PatchStep.DEACTIVATION_COMPLETE -> R.string.step_deactivate_complete
            PatchStep.COMPLETE,
            PatchStep.FORCE_DEACTIVATION,
            PatchStep.ERROR,
            PatchStep.CANCEL                -> _title.value
        }

        val currentTitle = _title.value
        aapsLogger.info(LTag.PUMP, "prepareStep: title before cond: $stringResId")
        if (currentTitle != stringResId) {
            aapsLogger.info(LTag.PUMP, "prepareStep: title: $stringResId")
            _title.postValue(stringResId)
        }

        patchStep.postValue(newStep)

        return newStep
    }

    enum class SetupStep { INITIAL, FILLED, PRIMED, ACTIVATED, ERROR, START_DEACTIVATION, STOPPED
    }

    val setupStep = MutableLiveData<SetupStep>()

    fun updateSetupStep(newSetupStep: SetupStep) {
        aapsLogger.info(LTag.PUMP, "curSetupStep: ${setupStep.value}, newSetupStep: $newSetupStep")
        setupStep.postValue(newSetupStep)
    }
}
