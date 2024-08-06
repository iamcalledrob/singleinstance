# singleinstance

## Introduction
Lightweight Kotlin/JVM library that prevents multiple concurrent instances of a Kotlin application.
Launch arguments from subsequent instances are communicated back to the first instance via a UNIX domain socket.

A Kotlin equivalent to [unique4j](https://github.com/prat-man/unique4j)

## Installation
Add the [jitpack](https://jitpack.io/) repository to your build file:
```kotlin
repositories {
    maven { url 'https://jitpack.io' }
}
```

Then add as a module dependency:
```kotlin
dependencies {
    implementation("com.github.iamcalledrob:singleinstance:1.0.0")
}
```

## Usage
Instantiate `SingleInstance` with a file path and call `args()`.
1. If this is the first instance, `args` will listen indefinitely for subsequent instances to connect to it. 
2. If there is an existing instance, `args` will connect and send its arguments, then exit.

```kotlin
fun main(args: Array<String>) {
    SingleInstance("/path/to/a/unique/file.sock").args(args) { receivedArgs ->
        // Handle received args from another instance
        println("Subsequent instance args: ${receivedArgs.joinToString()}")
    }

    // Handle original args
    println("Original launch args: ${args.joinToString()}")

    // The rest of the application logic
}
```

There is also a helper function for generating a suitable sock file path from an identifier:
```kotlin
socketPath(identifier = "org.foo.widget")
// -> /tmp/org.foo.widget.sock
```

## How it works
1. A system-wide file lock is used to determine which instance is "first" to launch
2. The first instance then listens on a domain socket for arguments from other instances
3. Other instances are unable to acquire the file lock, and instead connect to the first instance via the socket.
   Arguments are written to the socket, then the process exits.
4. When the original process exits, the file lock is released.

## Why you might consider this over [unique4j](https://github.com/prat-man/unique4j)
1. Uses [UNIX Domain Sockets](https://inside.java/2021/02/03/jep380-unix-domain-sockets-channels/), which avoids
   triggering the Windows firewall. unique4j listens on a local TCP socket, which will pop a firewall dialog.
2. Minimal design, leaves the exact implementation up to you.
3. Filesystem permissions can be used to control which processes are able to send arguments, if desired.

## Considerations
Domain sockets were added to Windows version 1809, so this library is unsuitable for legacy versions of Windows.