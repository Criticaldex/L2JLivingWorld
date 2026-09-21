# Optional AI chat brain

The bots in this server can hold in-character chat (whisper / say / trade / shout)
through a small local service called the "brain". It is **optional and off by
default** - the server and all the bots work fully without it; this only adds
the talking.

## What you need

`setup_brain.bat` sets everything up for you - it installs Python automatically
if it isn't already present. You just choose how the bots "think":

- **Ollama** - a free local model that runs on your own PC (needs a decent
  GPU/CPU; the setup installs Ollama and downloads a few GB the first time), or
- **DeepSeek** - a cloud API (works on any PC, needs an API key you paste in).

## Turn it on

1. Double-click **setup_brain.bat** in this folder. It installs Python if needed,
   asks whether to use Ollama or DeepSeek, sets everything up, and starts the
   brain on http://127.0.0.1:5000. After this first run it is remembered, so
   double-clicking it again just starts the brain - no more questions. To switch
   provider later, run **setup_brain.bat --reset**.
2. To have the launcher start the brain automatically with the server instead,
   set `StartBrain=true` in `launcher\launcher.ini`.

That's it - once the brain is running, the bots start chatting.

> If Python was just installed for the first time, Windows may need a fresh
> Command Prompt to see it - if the script says so, just double-click
> setup_brain.bat again.
