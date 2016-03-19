# Team Chat
This aims to be a peer-to-peer communication platform
for people who need to talk with a manner of security,
 or just wants a project to help clean up.

## Build Instructions
- JDK 1.8 or later
- IntelliJ IDEA
- Internet Connection (to download build dependencies)

Open the project, and Build/Debug the "Main" configuration.

## How to Run This App
This can either be used for its API or just a standalone console app.

 - Download the distribution zip (or make it using the provided generateRelease.bat)
 - Extract the zip
 - Run *[extracted\zip\path]*\bin\TeamChat.bat

### What to answer for the prompts
- **HOST:** type *localhost*, *127.0.0.1*, or provide the IP address for another machine with this already running
- **USERNAME** type something that starts with a letter
- **PASSWORD** - provide something random (8 character or longer), as we haven't completed the authentication structure

## API Usage
- Import ```org.teamclutch.chat.Chat``` in to your JVM-based application
- Use one of the constructors for ```Chat```
- Call ```.start()``` or ```.startAsync()```

### Receiving a message
- Write a method annotated with ```@Subscribe``` with the only parameter being ```DataMessage```
- Pass an instance of the class to the ```Chat``` constructor for the ```caller``` parameter

**or**

Call ```getNextMessage()``` for the chat instance; this will return when a message has been received

### Sending a message
Call ```newMessage(String)``` where String is the message you want to send

#### Notes:
- If you call ```newMessage("exit")```, your session will be terminated
- If your session is terminated, the ```newMessage(String)``` throws an ```IllegalStateException```

### Checking to see if the server is running
Use the ```isServerUp()```, which will return ```true``` if the server is running. The server will automatically
start whenever it can

## Issues
- When the currently connected server dies, the clients never seem to recoginze it.
- Formatting issues
- Needs to log better


By Team Clutch FTC5395 :)