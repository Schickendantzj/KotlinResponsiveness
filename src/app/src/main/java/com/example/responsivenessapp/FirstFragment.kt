package com.example.responsivenessapp

import android.os.Bundle
import android.view.Display
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.responsivenessapp.databinding.FragmentFirstBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

import org.responsiveness.main.JvmMain.main

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.outputText.setText("")

        binding.buttonStartTest.setOnClickListener {
            // TODO: remove this and refactor libary to exclude file writing when wanted.
            val path = context!!.getExternalFilesDir(null)!!
            val outputChannel = Channel<String>()
            var output = ""
            GlobalScope.launch (newSingleThreadContext("test")) {
                launch(newSingleThreadContext("output")) {
                    for (out in outputChannel) {
                        output = out +  "\n" + output
                    }
                }
                launch(newSingleThreadContext("updateText")) {
                    while (true) {
                        launch(Dispatchers.Main) {
                            binding.outputText.setText(output)
                        }
                        delay(100L)
                    }
                }
                main(arrayOf(""), binding.EndPointUrl.text.toString(), outputChannel, path, false)
            }
        }

        binding.buttonTestURL.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_test_url)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}