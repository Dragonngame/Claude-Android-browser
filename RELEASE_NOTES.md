## What's new

**Important: this version has a new signing key.** You have to **uninstall the old Remote Browser first** and then install this one. After that, set up the ngrok token and domain again, and update the connector URL on claude.ai (it gets a new secret key).

**Security**
- New private signing key. The old one was public in this repository, so anyone could have built a fake version that installs over yours. The new key's password only exists as a GitHub secret.
- **Blocked sites:** Claude can't open or stay on banking, MitID, borger.dk, skat.dk, e-Boks, sundhed.dk or email sites. Edit the list under Setup > More > Blocked sites.
- **run_js is off by default.** It's the most powerful tool, so it's now opt-in under Setup > More.
- **Activity log:** see everything Claude did, with times, under Setup > More > Activity log.

**Battery and convenience**
- Pages are closed 2 minutes after Claude's last request (when the app is in the background). Logins and cookies are kept, and the page reopens automatically if Claude needs it again.
- Setting for which version of sites to show by default (phone or desktop): Setup > More.
- The update check now also runs when you open the app, at most once an hour.
