import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import com.gaiaspa.metrics_detection.R

class ProgressDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Inflate the dialog layout
        val builder = AlertDialog.Builder(requireContext())
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_progress, null)
        builder.setView(view)
            .setCancelable(false) // Prevent dismissal by touching outside

        return builder.create()
    }
}
