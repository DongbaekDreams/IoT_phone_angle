package edu.iot.phoneangle.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.IdentityRegistry
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.databinding.FragmentHomeBinding
import edu.iot.phoneangle.databinding.ItemChecklistRowBinding

class HomeFragment : Fragment(), Refreshable {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var checklist: ChecklistState
    private lateinit var settings: ProjectSettings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        checklist = ChecklistState(requireContext())
        settings = ProjectSettings(requireContext())
        binding.nextStepButton.setOnClickListener { runNext() }
        binding.extraCreditButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), edu.iot.phoneangle.ExtraCreditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun refresh() {
        if (_binding == null) return
        val claimed = IdentityRegistry(requireContext()).isLocked
        binding.homeGreeting.text = if (claimed) {
            getString(R.string.home_greeting_named, settings.displayLabel())
        } else {
            getString(R.string.home_greeting_new)
        }

        val steps = checklist.steps()
        val next = checklist.nextAction()
        binding.nextStepButton.isVisible = next != null
        if (next != null) {
            binding.nextStepButton.text = getString(R.string.home_next_cta, next.title)
        }

        binding.checklistContainer.removeAllViews()
        for (step in steps) {
            val row = ItemChecklistRowBinding.inflate(layoutInflater, binding.checklistContainer, false)
            row.stepStatus.text = if (step.status == StepStatus.DONE) "✓" else ""
            row.stepStatus.setBackgroundResource(
                when (step.status) {
                    StepStatus.DONE -> R.drawable.bg_step_done
                    StepStatus.TODO, StepStatus.PREVIEW -> R.drawable.bg_step_todo
                    StepStatus.BLOCKED -> R.drawable.bg_step_blocked
                },
            )
            row.stepTitle.text = step.title
            row.stepDetail.text = step.detail
            row.stepTitle.alpha = if (step.status == StepStatus.BLOCKED) 0.45f else 1f
            row.stepDetail.alpha = if (step.status == StepStatus.BLOCKED) 0.45f else 1f
            row.root.setOnClickListener { openStep(step) }
            binding.checklistContainer.addView(row.root)
        }
    }

    private fun runNext() {
        val next = checklist.nextAction() ?: return
        openStep(next)
    }

    private fun openStep(step: ChecklistStep) {
        val host = activity as? ShellHost ?: return
        when (step.id) {
            "claim" -> host.openIdentityDialog()            else -> if (step.navId != 0) host.openTab(step.navId)
        }
    }
}
