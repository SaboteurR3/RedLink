# RedLink — what it does (product guide)

This guide explains RedLink from the business side: who uses it, what they can do, which rules
apply, and what you should see when you try it. No code knowledge is needed. At the end there is a
checklist for testing it yourself.

---

## 1. In one paragraph

RedLink is **mission control for a robot on Mars**. People on Earth use a web application to give
the rover orders ("drive 5 meters", "take a photo", "drill a sample"). The orders travel to Mars
over a radio link that is slow (in RedLink 10 seconds each way; on the real Mars 3–22 minutes)
and sometimes interrupted. The rover carries them out on a real map, and reports back what
happened, again with a delay. RedLink makes sure that no order is lost, no order is carried out
twice, orders run in the right order, dangerous orders are approved by a second person, and
everyone sees what is happening as soon as Earth hears about it.

The same problem exists on Earth wherever you control machines you cannot reach directly:
drones, delivery robots, electric-car chargers, factory machines, satellites. Mars is the extreme
version: the connection is always slow and sometimes gone.

## 2. The big picture

```
  People (browser)  ──►  Mission Control (Earth)  ── radio link, 10 s ──►  Rover (Mars)
        ▲                       │      ▲                                      │
        └── live updates ───────┘      └────────── reports, 10 s ◄────────────┘
```

- **Mission Control** is the "office" on Earth: users log in there. It keeps the orders and
  everything the rover has reported.
- **The rover** is a separate program, a simulation of the robot. It has its own memory and only
  receives orders through the radio link. It is the only one that knows where it *really* is.
- **The link** carries signed messages in both directions. Nobody else can send the rover an order.

The most important idea: **Earth never knows the present, only the past.** Everything the screen
shows is "what the rover told us last". When you send an order, the screen shows it travelling,
and the rover only moves on the screen when its report comes back.

## 3. Who uses it (roles)

There are three roles. Each one can do everything the role above it in the table can do.

| Role | Who in real life | Can |
|---|---|---|
| **Viewer** | scientists, managers, public | watch everything: map, rover, orders, photos, events; register notifications (webhooks) |
| **Operator** | the person who drives the rover | everything a viewer can, plus send orders, plan missions, cancel orders, rename the rover |
| **Flight Director** | the person responsible for safety | everything an operator can, plus approve or reject risky orders, and declare a solar conjunction |

Demo accounts (local): `viewer`, `operator`, `director`, all with password `redlink-dev`.

## 4. What the rover can do (the order list)

The rover understands exactly six orders. Anything else is refused.

| Order | What it does | Allowed values | Takes | Costs |
|---|---|---|---|---|
| **MOVE** | drives forward in the direction it faces | 1–20 meters, whole numbers only | 1 s per meter | 1 % battery per meter, 2 % in sand |
| **ROTATE** | turns to face North, East, South or West | one of the four | 2 s per quarter turn | 0.2 % per quarter turn |
| **TAKE_PHOTO** | takes a picture with a camera | NAVCAM (wide, black and white) or MASTCAM (narrow, colour) | 3 s | 0.5 % battery, 2 % storage |
| **DRILL_SAMPLE** | drills a soil sample | — | 15 s | 8 % battery, 5 % storage; needs at least 30 % battery; **needs approval** |
| **CHARGE** | charges from the solar panels | 1–120 minutes | 1 s per minute in the demo | +1 % battery per minute (only +0.25 % in a dust storm) |
| **REBOOT** | restarts the rover's computer | — | 5 s | leaves safe mode |

The map is the Jezero crater (simulated): 64 × 64 meters, with ground, sand and rocks, and hills
up to 4 meters. Rocks and the map edge cannot be driven through, and slopes steeper than 30° are
too steep.

## 5. The life of an order

When an operator sends an order, it goes through these steps. You can watch them in the
**Commands** list.

1. **QUEUED** — accepted by Earth. The answer comes **immediately**: "accepted", not "done". For
   **5 seconds** it waits on Earth; this is the last chance to **cancel**.
   - A **drill** waits instead as **PENDING_APPROVAL** until a flight director decides.
2. **TRANSMITTED** — sent to Mars. From now on it **cannot be called back**. A light beam flies
   to the rover on the 3D map, with a countdown ("reaches Mars in 8 s").
3. **RECEIVED** → **EXECUTING** (with a progress percentage) → **COMPLETED**. The rover reports
   each of these, and each report needs 10 s to reach Earth.
4. The order can also end as:
   - **FAILED** — the rover tried, but something stopped it (a rock, not enough battery, storage
     full, a stuck wheel). The reason is shown.
   - **REFUSED_BY_ROVER** — the rover did not even start it (for example, it is in safe mode).
   - **CANCELLED** — an operator withdrew it in time, or its mission was stopped.
   - **REJECTED** — a flight director said no (optionally with a reason).

A typical order: 5 s waiting, 10 s travelling, a few seconds of work, 10 s for the report — about
half a minute from click to "COMPLETED" on the screen.

## 6. The rules (business logic)

These are the rules RedLink enforces. Each one has a reason.

**Sending orders**

1. **An order is sent at most once**, even if the network breaks and the user's browser sends it
   again. The second attempt gets the first answer back; the rover never drives twice.
2. **Orders run in the order they were sent.** If one is lost on the way, the rover waits up to
   30 s for it, then skips it and says so (event `SEQUENCE_GAP_SKIPPED`). If it arrives later, it
   is refused as too late.
3. **Earth refuses what is certainly impossible** according to the last report:
   - in safe mode, only REBOOT is accepted;
   - drilling needs at least 30 % battery;
   - a drive needs at least 1 % battery per meter.

   Everything else is decided by the rover, which knows the truth.
4. **Wrong values are refused with a clear reason**, never "fixed" silently. For example, 1.5
   meters is refused ("must be a whole number"), not shortened to 1 meter.

**Safety**

5. **Drilling needs approval** by a flight director, and **not by the person who asked for it**
   (the "four-eyes" rule). A director's own drill must be approved by another director.
6. **Approval is checked again**: if the battery dropped below 30 % while the drill waited,
   approving it is refused.
7. **Safe mode:** if the rover hits a rock, the map edge or a too-steep slope, or runs out of
   battery while driving, it stops and goes into **safe mode**. It then refuses everything except
   **REBOOT** until Earth has looked at the situation. Problems that are not dangerous (storage
   full, low battery for a photo, a stuck wheel) only fail that one order.
8. **Cancel only while on Earth:** once an order is transmitted, cancelling it is refused
   ("conflict").

**Missions (plans)**

9. A **mission** is a named list of up to 50 orders. Before anything is sent, Earth **plays the
   whole plan forward** on the map, meter by meter, with the rover's real costs. If any step is
   impossible, the whole plan is refused, naming the step and the reason (e.g. "step 2: meter 4
   would hit a rock at (22,29)"), and **nothing** is sent.
10. If the plan is possible, Earth shows a **forecast**: where the rover will end up, facing
    which way, with how much battery and storage. On the 3D map a transparent "ghost rover" stands
    there.
11. A plan is only accepted when the rover has **no unfinished orders**, because otherwise Earth
    does not know where the plan starts ("rover busy").
12. Steps go out **strictly in order**. A drill step waiting for approval holds back the rest of
    the plan.
13. If a step fails, is refused, rejected or cancelled, the **steps still on Earth are cancelled
    automatically**, because they assumed that step would work.

**The link**

14. **Solar conjunction:** when the Sun is between Earth and Mars, a flight director declares a
    conjunction.
    - Orders are still accepted but **wait on Earth**.
    - The rover's reports **wait on Mars**: Earth tells the rover "try again in 30 seconds".
    - When the conjunction ends, everything flows again. **Nothing is lost.**
15. **Relay windows** (optional): orders can only leave during a regular contact window, for
    example 45 seconds every 2 minutes. Outside a window they simply wait.

**Mars itself**

16. **Dust storms** start and stop at random. During a storm, charging gives only a quarter of the
    normal energy. Earth gets a warning when a storm starts and a note when it ends.
17. **A wheel can get stuck** on any meter of a drive. The drive stops ("WHEEL_STUCK"), but it is
    not dangerous: just send the drive again.
18. Earth's mission forecast assumes normal conditions; storms and stuck wheels can make the
    reality differ.

**Accounts and access**

19. Logins last **15 minutes**; then you log in again.
20. **5 wrong passwords in 15 minutes** block further attempts from that computer for that
    username for a while. Other computers are not affected, so nobody can lock a real operator out.
21. Each user sees only **their own** webhooks.

## 7. What you see on the screen

- **Top bar:** "● live" when the live connection works; your name and role; Log out.
- **The map (3D):**
  - the crater with its rocks;
  - the rover where it was last reported (it glides when a new report arrives);
  - light beams for orders on their way;
  - small blue camera pins where photos were taken;
  - the ghost rover for a planned mission;
  - a red pulsing light in safe mode;
  - drifting dust (more of it in a dust storm).

  The buttons switch the camera between **Orbit** (drag to look around), **Follow** (behind the
  rover) and **Top-down**. **2D** shows a flat map instead (also used automatically on computers
  without 3D graphics).
- **Rover panel:** mode, battery, position and heading, temperature, storage, and how long ago the
  last report was made (Mars time).
- **Deep-space link panel:**
  - the light time;
  - the relay window (if enabled);
  - the conjunction banner, plus Declare/End buttons for flight directors;
  - orders in flight with countdowns;
  - the rover's events (dust storms, safe mode, …).
- **Photos:** the latest photo large, the others as thumbnails. The images are drawn by the rover
  from the map, from where it stood and the way it looked.
- **Command console (operators and directors only):**
  - choose an order and its value, then **Send to rover**;
  - or **Add to plan** several orders, give the plan a name and **Send mission**.

  The answer appears below: the order number and status, or the reason it was refused.
- **Commands list:**
  - every order, newest first, with its status;
  - for mission steps, a chip like `M3·2` (mission 3, step 2);
  - who sent it, the progress bar, and the failure reason.

  Buttons appear only when the action is possible right now: **Cancel**, and **Approve /
  Reject** for flight directors (not on their own drills).

Viewers see the same screen without the console and without action buttons.

## 8. For other systems

- **Webhooks:** any user can register an internet address to be notified when an order completes
  or fails, or when the rover enters safe mode or its battery drops below 20 %.
  - Each notification is signed with a secret that is shown **once** at registration, so the
    receiver can check it came from RedLink.
  - Failed notifications are retried with growing pauses.
  - Only public `https` addresses are allowed, so RedLink cannot be abused to reach internal
    systems. The local development setup relaxes this on purpose.
- **The API** behind the screen is documented and can be tried in the browser at
  <http://localhost:8080/swagger-ui.html>.
- **Photos** can be fetched as data (JSON) or as the image itself (PNG) from the same address.

## 9. What it does not do

- One rover and one map in the demo (the system supports more rovers, but only one is set up).
- No user management screens: the three demo users are created automatically.
- No real Mars data: the rover, the map, the photos and the weather are simulated.
- The planner does not predict dust storms or stuck wheels.
- Running everything with Docker is prepared but has not been tried on the development computer.

---

## 10. Test it yourself

Start everything as described in `docs/writeup/DEMO.md` ("Before the audience arrives"), then open
<http://localhost:5173>. Use a normal window for **operator** and a private window for **director**
(and a third for **viewer** if you like). Expected results are written after each arrow (→).

**Logging in**

- [ ] Wrong password → "Invalid username or password".
- [ ] Log in as viewer → no command console ("Viewers watch only"), no action buttons.
- [ ] Reload the page → you stay logged in. Log out, then reload → you stay logged out.

**A simple order**

- [ ] Operator: ROTATE → EAST, Send → "Command #… QUEUED".
- [ ] Watch it go TRANSMITTED, with a beam and a countdown, then RECEIVED, EXECUTING and COMPLETED.
      The rover turns on the map about 10 s after it finished on Mars.
- [ ] The viewer window shows the same changes live, without reloading.

**Cancel**

- [ ] Send MOVE 1 and click Cancel within 5 s → CANCELLED, and the Cancel button disappears.
- [ ] Wait for an order to be TRANSMITTED → it has no Cancel button any more.

**Approval (four eyes)**

- [ ] Operator: DRILL_SAMPLE → PENDING_APPROVAL, "waiting for another flight director".
- [ ] Director window: Approve → the operator sees "approved by director", then it runs.
- [ ] Director sends a drill themselves → they get no Approve button on it; Reject works.

**Mistakes are explained**

- [ ] Try values outside the limits, e.g. MOVE 25 → the browser itself refuses it (the field allows
      1–20). Through the API (Swagger UI), wrong values get a reason naming the field, e.g.
      `distanceMeters: must be less than or equal to 20` or `heading: must be one of NORTH, EAST,
      SOUTH, WEST`.

**Missions**

- [ ] Add to plan: TAKE_PHOTO, ROTATE, MOVE 3 → Send mission → a forecast; the ghost rover
      appears; the steps run in order (chips `M…·1`, `·2`, `·3`); the rover stops on the ghost.
- [ ] While orders are still running, send another mission → "Rover busy".
- [ ] A plan that drives into the map edge (several MOVE 20) → "Mission plan infeasible", naming
      the step; nothing appears in the commands list.

**Safe mode**

- [ ] Drive towards a rock (look at the 2D map for black cells) with a long MOVE → FAILED with
      "Rock at (…)", the rover shows SAFE MODE with a red light, and an event "SAFE_MODE_ENTERED".
- [ ] Try TAKE_PHOTO → refused: "rover in safe mode".
- [ ] REBOOT → back to NOMINAL.

**Photos**

- [ ] TAKE_PHOTO with each camera → the photo strip shows a new picture (NAVCAM gray, MASTCAM
      colour), and a blue pin appears on the map.

**The link**

- [ ] Director: Declare conjunction → every window shows the banner.
- [ ] Operator sends an order → it stays QUEUED.
- [ ] Director: End conjunction → the order flies.

**Views**

- [ ] Orbit / Follow / Top-down cameras; 2D and back to 3D. After a reload the 2D/3D choice is
      remembered.

If something behaves differently from the arrow, note the time, the user and what you clicked:
every order has a number, so it can be found in the logs.

---

## 11. Words used in RedLink

| Word | Meaning |
|---|---|
| **Mission control** | the Earth side: the web application and its server |
| **Rover** | the robot on Mars (simulated) |
| **Uplink / downlink** | Earth → Mars / Mars → Earth |
| **Light time** | how long a signal needs between Earth and Mars (10 s in the demo) |
| **Telemetry** | everything the rover reports: order progress, its state, events, photos |
| **Last known state** | what Earth believes about the rover, from its latest report |
| **Safe mode** | the rover's protective stop after something dangerous; only REBOOT gets it out |
| **Four-eyes rule** | a risky order needs a second person to approve it |
| **Mission** | a named, ordered plan of orders, checked as a whole before anything is sent |
| **Forecast** | where Earth expects the rover to be after a mission |
| **Solar conjunction** | the Sun blocks the link; everything waits until it is over |
| **Relay window** | a regular time slot when the link is available |
| **Webhook** | an automatic notification sent to another system's web address |
| **Idempotency key** | a unique number on each order request, so a repeated request is recognised and not carried out twice |
