package mina.infoxus.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mina.infoxus.Constants
import mina.infoxus.R
import mina.infoxus.blockers.FocusModeBlocker
import mina.infoxus.databinding.DialogFocusModeBinding
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.utils.NotificationTimerManager
import mina.infoxus.utils.SavedPreferencesLoader

class StartFocusMode(savedPreferencesLoader: SavedPreferencesLoader,private val onPositiveButtonPressed: () -> Unit) : BaseDialog(
    savedPreferencesLoader
) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val dialogFocusModeBinding = DialogFocusModeBinding.inflate(layoutInflater)
        val previousData = savedPreferencesLoader?.getFocusModeData()
        dialogFocusModeBinding.focusModeMinsPicker.setValue(3)
        dialogFocusModeBinding.focusModeMinsPicker.minValue = 2

        var selectedMode = previousData?.modeType
        if (previousData != null) {
            when (previousData.modeType) {
                Constants.FOCUS_MODE_BLOCK_SELECTED -> dialogFocusModeBinding.blockSelected.isChecked =
                    true

                Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED -> dialogFocusModeBinding.blockAll.isChecked =
                    true
            }
        }

        dialogFocusModeBinding.modeType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                dialogFocusModeBinding.blockAll.id -> selectedMode =
                    Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED

                dialogFocusModeBinding.blockSelected.id -> selectedMode =
                    Constants.FOCUS_MODE_BLOCK_SELECTED
            }
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setView(dialogFocusModeBinding.root)
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                val totalMillis = dialogFocusModeBinding.focusModeMinsPicker.getValue() * 60000
                savedPreferencesLoader?.saveFocusModeData(
                    FocusModeBlocker.FocusModeData(
                        true,
                        System.currentTimeMillis() + totalMillis,
                        selectedMode!!
                    )
                )
                sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_FOCUS_MODE)
                val timer = NotificationTimerManager(requireContext())

                timer.startTimer(totalMillis.toLong())
                onPositiveButtonPressed()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

}


