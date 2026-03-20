import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import com.gaiaspa.metrics_detection.R

class ProgressDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Infla el layout del diálogo
        val builder = AlertDialog.Builder(requireContext())
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_progress, null)
        builder.setView(view)
            .setCancelable(false) // Evita que se cancele tocando afuera

        return builder.create()
    }
}
