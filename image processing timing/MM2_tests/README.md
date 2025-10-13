# MM2_tests

Micro-Manager 2.0 Java benchmark tests for image acquisition and memory-mapped writing.

## Setup

1. Copy these files from your Micro-Manager 2.0 install:
   - `mmcorej.jar` → `lib/`
   - `MMCoreJ_wrap.dll` → `native/`
   - `MMConfigDemo.cfg` → `config/`

2. Build:
   ```bash
   javac -cp lib/mmcorej.jar -d bin src/MMHelloWorld.java
