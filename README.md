# Claude Android browser

An Android browser that Claude can control through a custom connector (MCP). ngrok is built into the app, so no Termux is needed.

Setup in the app: tap **Setup → ngrok settings**, paste your ngrok authtoken and your free ngrok domain. When the status line says **Online**, tap **Setup → Copy URL** and add that URL as a custom connector on claude.ai.

The app keeps running in the background (you'll see a notification) so you can chat in the Claude app while it works. Close it from **Setup → More → Stop and close the app**.

Tools: open_url, get_page, find_elements, click_element, type_text, press_key, scroll, screenshot, tap, go_back, run_js, wait.

Every push builds a new APK under Releases.
