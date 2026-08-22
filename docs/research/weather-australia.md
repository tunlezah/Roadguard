# Free weather data for an Australian offline dashcam

**Researched: 2026-08-22.** Question asked by the specification (§22): is there a weather
source that covers Australia, is free, and needs **no registration, no API key, no
subscription and no cloud account**? If not, weather must not be implemented at all.

**Answer: yes — Open-Meteo.** The Bureau of Meteorology, which the specification would have
preferred, does not qualify. Weather therefore ships, sourced from Open-Meteo, behind a
setting that is **off by default**.

---

## 1. The Bureau of Meteorology does not qualify

BoM is the obvious first choice: it is the Australian national meteorological authority, its
data is public, and it publishes JSON at `.../fwo/IDxxxxxx.json`-style paths that developers
have historically read directly.

It was ruled out on evidence, not on assumption. Fetching `http://www.bom.gov.au/robots.txt`
from this build environment returned not a robots file but BoM's automated-access block page,
which states:

> Your access is blocked due to the detection of a potential automated access request. The
> Bureau of Meteorology website does not support web scraping: if you are trying to access
> Bureau data through automated means, you should stop.

The same page directs programmatic consumers to two alternatives:

* an **anonymous FTP** channel intended for bulk file transfer, not for a phone making an
  occasional point request; and
* a **Registered User service**, of which the page says *"charges apply to most data
  products"*.

Both fail the specification's test. The FTP channel requires an account-free but wholly
unsuitable access pattern (whole-product file pulls, no point query, no rate story for
thousands of installs), and the registered service requires registration and money — two of
the four things §22 rules out.

There is a second, independent reason not to scrape BoM even if it were technically possible:
a dashcam installed on many phones that scrapes a government site the site says not to scrape
is abusive traffic. Roadguard does not do that.

**Conclusion: BoM is not usable, and this is a property of BoM's access policy, not a
limitation of Roadguard.**

## 2. What else was considered

| Source | Covers Australia | Free | No key | No account | Verdict |
| --- | --- | --- | --- | --- | --- |
| Bureau of Meteorology (web/JSON) | yes | yes | n/a | n/a | **Rejected** — blocks automated access; points at a charged registered service |
| BoM anonymous FTP | yes | yes | yes | yes | **Rejected** — bulk-file channel, no point query, wrong access pattern for per-device use |
| OpenWeatherMap | yes | free tier | **no** | **no** | Rejected — API key and account required |
| WeatherAPI.com | yes | free tier | **no** | **no** | Rejected — API key and account required |
| Tomorrow.io / Visual Crossing / AccuWeather | yes | trial tiers | **no** | **no** | Rejected — key and account required |
| MET Norway Locationforecast | yes (global) | yes | yes | yes | Viable, but requires a contact address in `User-Agent` and is explicitly for "small" use; kept as the documented fallback |
| **Open-Meteo** | **yes** | **yes** | **yes** | **yes** | **Selected** |

## 3. Why Open-Meteo qualifies

Open-Meteo's own terms describe the free tier as:

> Less than 10'000 API calls per day, 5'000 per hour and 600 per minute. You may only use the
> free API services for non-commercial purposes. You accept to the CC-BY 4.0 licence

There is no key parameter, no sign-up step and no account. The data is a blend of national
weather-service models (including ACCESS-G, the Bureau's own global model) served under
CC-BY 4.0, which is why the app carries the attribution string
`Weather data by Open-Meteo.com, CC BY 4.0` on the About screen and next to the weather
readout.

Rate limits are not a practical concern: Roadguard refreshes at most every 15 minutes at
`ThermalLevel.Normal` and stretches to 120 minutes at `Critical`, only while recording, and
only when the user has turned weather on.

## 4. How it is implemented, and the privacy constraint

`weather/OpenMeteoWeatherSource.kt`:

* endpoint `https://api.open-meteo.com/v1/forecast`, `current=` fields only;
* **coordinates are rounded to two decimal places before the request is built**
  (`COORDINATE_PRECISION = 2`, about 1.1 km at Australian latitudes). A 1.1 km cell cannot
  identify a street, let alone an address, and is far finer than any weather model's own grid,
  so nothing is lost meteorologically;
* the request carries no identifier, no session, no track and no history — one rounded point
  and a list of field names;
* WMO code 4677 is translated to text locally, so no locale or language hint is sent either;
* failures are silent and non-fatal. Weather is decoration; recording never waits for it, and
  a failed request never surfaces as an error that could distract a driver.

This is one of only two network calls Roadguard ever makes. The other is the one-time offline
map download. Neither ever carries video, audio, GPS tracks, telemetry or diagnostics — see
`docs/privacy.md`.

## 5. What would change this decision

* If BoM published a free, keyless, point-query API with an access policy that permitted it,
  Roadguard should switch: national-authority data is preferable on principle. The
  `WeatherSource` interface exists so that is a one-file change.
* If Open-Meteo introduced a key requirement, the correct response is to switch to MET Norway
  (which needs only an identifying `User-Agent`) or to remove the feature — not to sign up for
  anything.

## 6. Honest limits

* The figures shown are a **model nowcast for a 1.1 km cell**, not an observation from a
  nearby weather station. It is contextual information for a recording, not a forecast to
  drive on.
* No accuracy testing against Australian observations was performed. None is claimed.
* Nothing here was tested on a phone. The endpoint construction, rounding and code table are
  covered by reasoning and by reading Open-Meteo's published contract; the request itself has
  not been made from an Android device in this work.
