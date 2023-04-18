package org.responsiveness.main

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

interface Main {
    fun main(args: Array<String>, outputChannel: Channel<String>) : Unit

    fun tryMain(): Unit {
        val outputChannel = Channel<String>()
        GlobalScope.launch {
            for (output in outputChannel) {}
        }
        main(args = arrayOf<String>("arg1", "arg2"), Channel<String>())
    }
}

object LGCFactory {
    fun start(): Unit {

    }

}

expect object MainFactory {
    fun createMain(): Main
}