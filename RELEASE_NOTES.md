## What's new

**Battery**
- The phone is allowed to sleep. The app only keeps the CPU and Wi-Fi awake while Claude is using the browser, and lets go 30 seconds after the last request.
- When the app is in the background and Claude hasn't used it for 1 minute, the page is frozen (no scripts or timers running). After 15 minutes it's unloaded completely and reopened automatically the next time Claude needs it.
- Ads and trackers are blocked, so pages are lighter.
- The app asks once to run without battery restrictions, so Android doesn't cut its connection while the screen is off. It's also under Setup > More.

**Browsing**
- New tool: get_images, which sends Claude the photos on a page (like a Facebook post).
- New tool: video_frames, which takes frames spread across a video (no audio).
- New tool: get_transcript, which reads a YouTube video's subtitles.
- New tool: find_text, which finds text on a page and scrolls to it.
- New tool: set_view, which switches between the phone and desktop version of sites.
- open_url now returns the page text right away.
- get_page now expands "See more", closes cookie and "log in" popups, and removes Facebook's clutter.
- If a tap doesn't do anything, the app automatically clicks with a script instead.

**App**
- Pause button in the notification: stops Claude from using the browser until you tap Resume. Also under Setup > More.
- The app checks GitHub for new versions and offers to download them. Also under Setup > More > Check for updates.
- New pixel robot icon.
- Removed "keep screen on".
