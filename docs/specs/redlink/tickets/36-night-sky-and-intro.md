# 36 — Shining night sky and a more beautiful intro

Status: ready-for-human

## Scope

Frontend only.

- **Night sky** (`scene/StarField.tsx`, used by `Sky`). It replaces drei `Stars`, which looked like dust:
  - 3,060 stars in three brightness classes, coloured from blue-white to orange;
  - glow sprites, so the bloom makes the brightest ones shine;
  - gentle twinkling;
  - they fade in from dusk instead of appearing only at deep night, and are hidden in a dust storm;
  - they sit on a sphere behind the crater rim, so the mountains cover them.
- **Intro** (`scene/Intro.tsx`):
  - Earth and Mars painted from noise (continents, oceans, ice caps, Earth's clouds; Mars' dark
    regions, polar caps, craters and a canyon);
  - an atmosphere glow at each planet's edge;
  - the sun with a bloom flare, lighting the planets from the side;
  - a command's radio pulse with a trail racing from Earth to Mars;
  - letterbox bars and the RedLink title;
  - the flight ends on the lit side of Mars, then fades into the site.
  - Same length and the same skip, Esc and reduced-motion rules as before.

## Acceptance criteria

- [x] Stars are clearly visible at night, the brightest glow, and the mountains hide them.
- [x] The intro shows Earth, the pulse crossing to Mars, and a lit Mars, then fades into the site.
- [x] The pulse's trail is gone once the pulse arrives.
- [x] Type check, Vitest (61) and build green.

## Verification

- Headless Edge on the GPU, at 1600 × 900:
  - screenshots at 0.9, 2.6, 4.2, 6.0 and 7.0 s of the intro;
  - a low view at night (sol 23:16) for the stars.
- Debugging note: a straight dark edge on Mars was the day/night line seen from the dark side, not
  a rendering bug (it went away with flat lighting). The fix was to end the flight on the sunlit side.
- Bump maps were taken off the planets: on a sphere they leave a crease at the texture's seam.
