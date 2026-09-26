# Burn-in Tool User Manual

Burn-in Tool is an Android headphone burn-in tool. It plays noise, sweeps or local music you import to help a new headphone's diaphragm settle faster. This manual is for users and explains how each feature works. For implementation details and build instructions, see [README_EN.md](../README_EN.md).

The app supports Android 8.0 and later. Apart from checking for updates and downloading update packages, which anonymously access GitHub public pages, burn-in works entirely offline, needs no account, and is free and open source.

## Install and get the app

App packages come from GitHub Releases. Each version ships two APKs, split by device CPU architecture (ABI). There is no universal package.

- `arm64-v8a`: for 64-bit devices.
- `armeabi-v7a`: for older 32-bit devices.

File names look like `open-burnin-tool-v1.5.0-arm64-v8a-release.apk`. In-app updates match assets the same way: `arm64-v8a` first, then `armeabi-v7a`, and only release packages. If you are not sure which one fits your device, use the in-app update, which picks the right package automatically.

When installing manually from a Release, you must first allow installation from unknown apps.

## Plan burn-in

Plan burn-in plays four phases in a fixed order, for a long burn-in run on a set rhythm. Plans come in two kinds, standard and custom, and the phase layout below applies to both.

### Standard four phases

The standard plan runs 120 hours in total and plays its four phases in order.

| Phase | Content | Duration | Default loudness |
| --- | --- | --- | --- |
| Gentle | White noise | 12 hours | 20% |
| Adapt | Pink noise | 12 hours | 33% |
| Steady | Constant pink noise, replaceable with local music | 72 hours | 47% |
| Rotation | White noise and pink noise alternating every 30 minutes | 24 hours | 60% |

### Custom four phases

A custom plan sets its own total duration, from 8–240 hours, 48 hours by default, with the stepper changing it by 12 hours at a time. The four phases scale to that total at a 10/10/60/20 ratio, which means Gentle 10%, Adapt 10%, Steady 60% and Rotation 20%. The Rotation phase still alternates every 30 minutes.

### Reorder the phases

The four phases default to the order Gentle, Adapt, Steady, Rotation. To change it, long-press the drag handle at the left of a phase row and drag; releasing commits the new order. The change is remembered and is still in effect the next time you open the app.

### Adjust phase loudness

Each row shows its phase's loudness at the end of the row. Tap it to change the value, which runs from 1%–100%. In the dialog you can step the value up or down by 5% at a time, or type the percentage directly. The default follows the standard plan's 20%, 33%, 47% and 60% (underlying ratios 1/5, 1/3, 7/15 and 3/5). A change affects only the phases you edited, and Reset to default in the dialog clears that phase's override.

During plan burn-in, phase loudness is expressed through the system media volume. When playback starts the app records the volume at that moment, then adjusts it by the phase ratio, and restores the original volume on pause or when the burn-in ends. If setting the system volume fails (for example in Do Not Disturb mode), this session falls back to player-level gain; if the process is killed, the original volume cannot be restored. So the system media volume changes during burn-in, and that is expected. Free burn-in does not use the system volume for loudness; see below.

### Use local music for the Steady phase

The Steady phase plays pink noise by default. Tap the edit icon on its row to open a dialog where you can switch between constant pink noise and local music. After choosing local music, tick one or more tracks to build an ordered playlist that plays in order on a loop, one track after the next. When the plan reaches the local music phase, the track name in the notification updates to match.

If a track in the playlist cannot be read, playback skips it and continues to the next available track, without falling back as a whole. Only when ticked tracks are deleted from local music and the playlist becomes entirely invalid does it fall back to constant pink noise; deleted tracks are dropped from the playlist automatically.

### When configuration takes effect

Phase order, loudness and the Steady phase content are saved immediately and remembered. Tapping Start or Resume builds the plan from the configuration at that moment. The same holds when you resume: the new configuration you changed applies. The plan's own identity does not change with the layout, so a resumed session still matches the original one.

## Free burn-in

Free burn-in has no fixed phases: pick a sound source and a duration and playback starts. It suits choosing what you listen to and for how long.

For the sound source you can choose one of the 7 built-in synthesized sources from the dropdown, or choose imported local music. The dropdown groups entries under Built-in sounds and Local music.

Duration can be set in two ways, and you pick one. The presets are 2, 8, 16, 24, 48 and 72 hours, 8 hours by default; or set a custom value from 1–999 hours.

Free burn-in expresses loudness as player-level gain, so your system media volume is not changed.

## Sound sources

### Synthesized sources

7 synthesized sources are built in and ready to use, with no files to prepare. They differ mainly in their spectrum and in whether they change over time.

| Source | Characteristics |
| --- | --- |
| White noise | Equal energy at every frequency; the standard plan uses it for the Gentle phase |
| Pink noise | Energy falls as frequency rises, with more energy at low frequencies; the standard plan uses it for the Adapt and Steady phases |
| 300 Hz sine wave | A pure tone at a single frequency |
| 150 Hz square wave | A 150 Hz square wave, which contains harmonics as well as the fundamental |
| Low-frequency sweep | Sweeps back and forth between 100 and 200 Hz, one cycle every 40 seconds |
| Wide sweep | Sweeps back and forth between 100 Hz and 10 kHz, one cycle every 74 seconds |
| Mixed burn | Half white noise, half pink noise |

Which one to pick depends on the frequency range you care about. To cover only low frequencies, choose low-frequency sweep, square wave or sine wave; to cover a wide range, use wide sweep or mixed burn; white noise and pink noise spread across the whole spectrum instead of focusing on one point.

### Local music

Import audio files through the system file picker. Files are copied into the app's private directory and played from there on a loop, so moving the original file later makes no difference. An imported file can be removed at any time; removal asks for confirmation, then deletes the file and clears the matching record.

Local music works as a free burn-in sound source, and can also replace pink noise in the Steady phase of a plan burn-in.

## Playback and background

### Start, pause, resume, stop

Tapping Start on a plan card or in free burn-in opens the playback screen, where the configuration area is replaced by the progress display. You can pause and resume at any time while playing. Stopping asks for confirmation: opening the dialog pauses playback and freezes the burned-in time at that moment, and cancelling resumes playback automatically; but if playback was already manually paused before the dialog opened, cancelling does not resume it.

### Resume from a checkpoint

When a plan has an unfinished session, its card shows Last progress and offers two entries. Resume continues from the seconds already completed, while Start fresh discards the old checkpoint and starts the count again. A plan keeps at most one resumable checkpoint.

### Notification controls

While playing, a notification stays visible with a countdown of the remaining time and three buttons: Pause, Resume and Stop. Tapping the notification returns to the app.

### Headphone unplug and call interruption

Unplugging the headphones pauses playback immediately. A brief interruption such as an incoming call pauses playback, which resumes automatically once audio focus returns; if focus is held permanently, playback stays paused and you resume it manually.

### Background playback and restore

A foreground service keeps playback alive after the app goes to the background. Progress is written to the database every 60 seconds, and also on pause, resume, stop and completion. If the process is still running, reopening the app returns straight to the in-progress screen, with the phase and sound source position preserved.

### Completion notification

When the planned duration is reached, the app posts a Burn-in complete system notification, whose burned-in time is frozen at the moment of completion; tapping it returns to the app. The session is marked completed, and the app also shows a prompt. Without notification permission the completion notification is skipped silently, without affecting the burn-in itself.

## Progress and history

The top of the History page shows a cumulative summary with the total burn-in time and the session count, and a clear entry on the right.

Below is the history list, 20 items per page, ordered from newest to oldest start time. Scrolling near the bottom loads the next page automatically, and the end of the list reports how many items are loaded in total. Each row shows the session's time, status, plan and planned duration, and the actual burned-in time. There are four statuses: In progress, Paused, Completed and Stopped. Free burn-in sessions also show the sound source used at the time after the plan; when that source is local music, it shows the track name from the start of the session, or Local music if that track has since been deleted.

Clearing all records asks for confirmation first, then deletes every record and resets the cumulative statistics. This cannot be undone.

## Settings

### Keep screen on

When on, the playback screen stays awake. The switch is available on the Settings page, in the Playback group.

### Anti screen-off

Anti screen-off is like keep screen on in that the screen stays awake during playback; the difference is what happens after the timeout. With Anti screen-off on, a long period without input first dims the screen to minimum brightness instead of turning it off. The reason it exists is that some devices interrupt background playback as soon as the screen turns off, and dimming saves power without interrupting the burn-in.

Touching the screen, pausing or stopping the burn-in restores normal brightness. While paused, or when both Keep screen on and Anti screen-off are off, brightness goes back to system control.

### Theme

Three modes: follow system, force light, force dark. The top bar of the burn-in page also has a quick light/dark toggle: in light mode it shows a moon icon and tapping switches to dark; in dark mode it shows a sun icon and tapping switches to light. It is the same setting as the theme mode on the Settings page; the switch takes effect immediately and is remembered. Under follow system, it inverts whichever of light or dark is currently shown.

### Dynamic color and preset palettes

Android 12 and later can use dynamic color, which derives the palette from the system wallpaper and is on by default. Dynamic color takes priority over the preset palettes. Older versions lack this capability, and the switch is greyed out.

With dynamic color off you can pick a preset palette. There are 6: Celadon green (default), Indigo, Warm amber, Rose, Forest green and Cerulean.

### Language

Choose it under Settings > General > Language, from three options: follow system, Chinese and English. The default follows the system: a Chinese system shows Chinese and any other language shows English. Switching takes effect immediately, and a notification from background playback changes language too. The choice is remembered and stays in effect after you restart the app.

## In-app updates

The app gets new versions from this repository's GitHub Releases. Checking anonymously fetches public pages, with no account, token or extra configuration, and does not go through the GitHub API.

### Automatic check

A silent check runs once at each process start. When a newer version is found, a dialog asks Later or Download and install; when the app is up to date or the check fails, nothing interrupts you.

### Manual check

The entry is Settings > About > Check for updates. Tapping it first shows a Checking for updates dialog, and the result then appears in a dialog you close with a confirm button. Three results are possible:

- The app is already up to date.
- A new version exists, but no package matches this device.
- The check failed, and the dialog gives the reason, such as the network being unavailable.

If the new version has a package for this device, the dialog shows the version number, the matching architecture and the package name. Confirming means Download and install, and cancelling means Later.

### Download and install

The download shows a progress bar, live network speed, and the downloaded and total size. The package is downloaded into the app's cache directory and then handed to the system installer, where you confirm the install; nothing is installed silently. A failed download cleans up the incomplete file and shows a prompt. If the system does not yet allow this app to install unknown apps, the app guides you to the system settings page to enable it.

### The three "Later" options

For automatic prompts, you can choose how long to skip:

- This session: skips only in the current process, is not saved, and the prompt appears again after a restart.
- 7 days: no automatic prompt for 7 days, then it expires on its own.
- Next version: skips only the current version; a higher new version still prompts.

These three options affect automatic prompts only. Manual checks are unaffected and always give a result.

## FAQ

### "Version x.y.z is available, but no package matches this device's architecture"

This means no release package matches the device's architecture. The usual cause is that the Release has no asset for that ABI, or that the version in the Release tag does not match the version in the package file name, so no match is found. You can go to the GitHub Release page and pick the APK for your architecture to download.

### The installer reports a signature conflict

The system requires an update package that overwrites the installed version to use the same signing key, otherwise the installer refuses it. Make sure the package comes from the same release channel and is not a redistributed build signed with a different key.

### The update downloads but will not install

When the new package's versionCode is lower than the installed version, the system installer refuses the downgrade; publish with an increasing versionCode. In this case you have to wait for the publisher to correct the version number and release again.

### Background burn-in stops partway

Some devices restrict or interrupt background playback after the screen turns off. You can turn on Anti screen-off in Settings, or also turn on Keep screen on, to keep the screen from going off and triggering the restriction.

### No "Burn-in complete" notification

When the app does not have notification permission, the completion notification is skipped silently. Grant notification permission for the app in system settings.

### The update check fails

Checking for updates needs network access to GitHub public pages, so it fails when the network is unavailable or access is restricted. The dialog shows the specific reason, and you can retry later.

## Related documents

- [README.md](../README.md): project description and build instructions
- [README_EN.md](../README_EN.md): project description, English
- [docs/PRODUCT.md](PRODUCT.md): product definition and feature list
- [docs/DESIGN.md](DESIGN.md): design system
