# Hertz: an offline AI agent for the Galaxy S25

Hertz's brain, **Gemma 4 E4B**, runs entirely on your phone through Google's LiteRT-LM runtime on the Snapdragon GPU. It can:

- **Chat and talk:** type or hold the orb to speak. It replies in a British butler voice.
- **See and use your screen:** it reads what's on screen, then taps, types, scrolls, swipes, presses Back/Home, and opens apps.
- **Use the internet:** it searches the web, reads any page or URL, and downloads files, then tells you the answer and where it came from.
- **Manage files:** it can list, read, create, edit, move, copy, delete and search files across internal storage and USB drives, and open any file in its app.
- **Float over everything:** a draggable orb that pulses while it works and shows what it's doing.
- **See the screen:** it can take a screenshot and look at it when text alone isn't enough.
- **Remember you:** long-term memory of facts you tell it, shown in the Memory tab.
- **Direct phone tools:** alarms, timers, calendar, calls, texts, flashlight, volume and music controls.
- **Notifications:** reads them and replies to messages for you.
- **Scheduled tasks:** does jobs by itself at set times or when you plug in your phone.
- **Hey Hertz:** optional hands-free wake word that works offline.
- **Expert brain:** optionally asks a bigger online AI (your API key) for hard questions.
- **Suggest:** it reads your current screen and proposes three next actions.

## Build and install (phone only)

1. Every push to `main` makes the **Actions** tab build `Hertz.apk`.
2. When the build is done, open the repo's **Releases → latest** page and tap **Hertz.apk**.
3. Open the downloaded file and allow "Install unknown apps" for your browser.
4. To change the code from your phone, edit any file on github.com (pencil icon) and commit. A new APK builds automatically, and it installs over the old one.

## First run

1. **Setup → Download brain.** This is 3.7 GB, so use Wi-Fi. It happens once.
2. **Screen control.** Go to Settings → Accessibility → Installed apps → **Hertz agent** → On.
   Android blocks this for sideloaded apps. If the switch is greyed out, open **Hertz App info → ⋮ → Allow restricted settings** and try again.
3. **All files access** and **Microphone:** tap *Allow* for each in Setup.
4. **Stay awake in background:** allow this so One UI doesn't kill Hertz mid-task.
5. Optional: install **Speech Services by Google** from the Play Store for the best British voices.

## Using it

- **Orb:** tap it for the command panel, hold it to talk, drag it to move it.
- Examples: "Open Settings and turn on dark mode", "Move all PDFs in Download into Documents/PDFs", "Write a shopping list note with eggs, milk, bread", "Every weekday at 7, read me my calendar and the weather", "What's on my screen?".

## Code map

| File | What it does |
|---|---|
| `llm/HertzBrain.kt` | Loads the model (GPU first, CPU as a fallback), runs the conversation, streams replies |
| `llm/ModelManager.kt` | Downloads or imports the `.litertlm` model |
| `agent/HertzTools.kt` | Every action Hertz can take. Add a `@Tool` function here to teach it something new |
| `agent/Agent.kt` | Task runner used by chat, voice, overlay, suggestions and scheduled tasks |
| `agent/Memory.kt` | Long-term memory and scheduled tasks |
| `device/HertzAccessibilityService.kt` | Reads the screen, taps, types, gestures and system keys |
| `device/Overlay.kt` | The floating orb, caption and command panel |
| `voice/Voice.kt`, `voice/NeuralVoice.kt` | British voices (phone TTS and the free on-device neural voice) and speech recognition |
| `ui/*` | Chat, Memory and Setup screens, plus the logo drawn in code |

Hertz acts without asking for confirmation. Stop it any time with the orb's **■ Stop** button or the red stop button in chat.
