package mina.infoxus.ui.fragments.anti_uninstall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import mina.infoxus.R
import mina.infoxus.databinding.FragmentChoseAntiUninstallModeBinding

class ChooseModeFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "choose_anti_uninstall_mode"
    }
    private var _binding: FragmentChoseAntiUninstallModeBinding? = null
    private val binding get() = _binding!!  

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChoseAntiUninstallModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnNext.setOnClickListener {
            when (binding.radioGroup.checkedRadioButtonId) {
                binding.passMode.id -> {
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(
                            R.id.fragment_holder,
                            SetupPasswordModeFragment()
                        ) 
                        .addToBackStack(null)
                        .commit()
                }

                binding.timedMode.id -> {
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(
                            R.id.fragment_holder,
                            SetupTimedModeFragment()
                        ) 
                        .addToBackStack(null)
                        .commit()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

