# Preliminary
1. Create a simple library in Kotlin
2. Create a simple App with a GUI in Android Studio
3. Test simple library call in Android App

# Design
4. Research and a choose a method to pipe/channel information from library to apps
5. Research API design
6. Research Library design
7. Establish necessary parameters to run test
8. Research multithread/process within Kotlin (JVM and Android OS)

# Core Test Functionality (all within Kotlin Library)
9. Implement and test HTTP2 connections and functionality, (Client Class)
   - Create tests for functionality
10. Implement server establishment (JSON Return) Class
    - Retrieve JSON
    - Parse test URLs
    - Track advanced information: server/client ID, specific node IP
11. Implement Load Generating Connections (Class)
    - Download large file
    - Upload large file
    - Track information: throughput speed, response time, TLS handshake time
    - Track advanced information: congestion window size
12. Implement Algorithm utilizing above Classes
13. Implement Config Structure/Class
    - Time to end of test
    - JSON URL
14. Test results from algorithm in comparison to GoResponsiveness
15. Implement a pipe/channel to the above classes to produce information
    - Alternatively create a data channel class to "transmit" data

# Testing (within Library)
16. Write tests for Client Class
17. Write tests for JSON Class
18. Write tests for Load Generating Connections Class
19. Write tests for full algorithm (Class?)
20. Write tests for pipe/channel Class

# GUI
21. Create Input GUI
    - Must be able to pass configs to config class/struct and start test
    - Must be able to transition to Test URL GUI, Interim GUI, Results GUI
22. Create Test URL GUI
    - Either utilize library code to pull JSON or write new code to test JSON URL
23. Create Results GUI
    - Must be able to receive results from library
24. Create Interim GUI
    - Must be able to receive pipe/channel data from library
    - Optionally, allow graph customization