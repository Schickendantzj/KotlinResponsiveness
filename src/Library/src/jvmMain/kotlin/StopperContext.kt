package org.responsiveness.main


// Class meant to be used to stop all launched coroutines across the board "safely"
class StopperContext(stop: Boolean) {
    private class Stopper (
        var name: String,
        var reason: String = "Context Stopper named: ${name}, has no reason provided",
        var stop :Boolean = false
    ) {
        // Equals when name and reason are the same.
        override fun equals(other: Any?): Boolean {
            return (other is Stopper) && other.name == this.name && other.reason == this.name
        }
    }

    // TODO: Consider using an array instead
    private var stoppers: ArrayList<Stopper> = ArrayList<Stopper>()

    // Returns false if the stopper already exists; true on add
    fun registerStopper(name: String, reason: String=""): Boolean {
        val stopper: Stopper
        if (reason != "") {
            stopper = Stopper(name, reason)
        } else {
            stopper = Stopper(name)
        }

        if (stopper in stoppers) { return false } // Do not add if exists
        stoppers.add(stopper)

        return true

    }

    // If stopper exists returns true and triggers stopper (starts stops); false if stopper does not exist
    fun activateStopper(name: String, reason: String=""): Boolean {
        val stopper: Stopper
        if (reason != "") {
            stopper = Stopper(name, reason)
        } else {
            stopper = Stopper(name)
        }

        for (i in this.stoppers) {
            if (i == stopper) {
                i.stop = true
                return true
            }
        }
        return false
    }

    // Returns true if any stoppers are true
    fun shouldStop(): Boolean {
        for (stopper in this.stoppers) {
            if (stopper.stop) {
                return true
            }
        }
        return false
    }

    // Returns a string of the reason why we are stopping
    fun reason(): String {
        // TODO MAKE THIS PRETTY
        var out = ""
        for (stopper in this.stoppers) {
            if (stopper.stop) {
                out += "\n" + stopper.reason
            }
        }
        return out
    }
}