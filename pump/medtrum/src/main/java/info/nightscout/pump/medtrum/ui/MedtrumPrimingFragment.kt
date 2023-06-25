package info.nightscout.pump.medtrum.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.pump.medtrum.R
import info.nightscout.pump.medtrum.code.PatchStep
import info.nightscout.pump.medtrum.databinding.FragmentMedtrumPrimingBinding
import info.nightscout.pump.medtrum.ui.MedtrumBaseFragment
import info.nightscout.pump.medtrum.ui.viewmodel.MedtrumViewModel
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import javax.inject.Inject

class MedtrumPrimingFragment : MedtrumBaseFragment<FragmentMedtrumPrimingBinding>() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper

    companion object {

        fun newInstance(): MedtrumPrimingFragment = MedtrumPrimingFragment()
    }

    override fun getLayoutId(): Int = R.layout.fragment_medtrum_priming

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            viewModel = ViewModelProvider(requireActivity(), viewModelFactory).get(MedtrumViewModel::class.java)
            viewModel?.apply {
                setupStep.observe(viewLifecycleOwner) {
                    when (it) {
                        MedtrumViewModel.SetupStep.INITIAL,
                        MedtrumViewModel.SetupStep.FILLED -> Unit // Nothing to do here, previous state
                        MedtrumViewModel.SetupStep.PRIMED -> moveStep(PatchStep.PRIME_COMPLETE)

                        MedtrumViewModel.SetupStep.ERROR  -> {
                            moveStep(PatchStep.ERROR)
                            updateSetupStep(MedtrumViewModel.SetupStep.FILLED) // Reset setup step
                            binding.textWaitForPriming.text = rh.gs(R.string.priming_error)
                            binding.btnPositive.visibility = View.VISIBLE
                        }

                        else                              -> {
                            ToastUtils.errorToast(requireContext(), "Unexpected state: $it") // TODO: String resource and show error message
                            aapsLogger.error(LTag.PUMP, "Unexpected state: $it")
                        }
                    }
                }
                startPrime()
            }
        }
    }
}
