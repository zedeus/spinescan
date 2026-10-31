# Eval

The eval measures how well the scanning pipeline identifies board games from shelf photos.

## Labels (ground truth)

Each photo has a label file in `testset/labels/{photo_basename}.json`:

```json
{
  "photo": "online/shelf-kallax.jpg",
  "set": "tune",
  "draft": true,
  "games": [
    {"name": "Catan", "legible": true},
    {"name": "Blurry Game", "legible": false}
  ]
}
```

- **photo**: path relative to `testset/photos/`
- **set**: `tune` (threshold tuning) or `holdout` (what counts)
- **draft**: `true` until the user approves via the checklist page; the eval marks draft-based results
- **games**: every game visible in the photo, left to right, top to bottom
  - **name**: the game's BGG primary name (English)
  - **legible**: whether the title text is readable in the photo

Labels are independent ground truth — they are never produced from pipeline output. The drafting step must not see OCR results. Only approved labels (`"draft": false`) are used for metrics that gate changes; draft labels are reported separately.

## Wrong green

A **wrong green** is a green box whose matched game is not among the labelled games in that photo. This is the most important metric: a wrong green costs the user's trust in the whole library; an orange costs a tap.

Matching a detection to a label uses the game name (normalised). A green box whose match name normalises to any labelled game's normalised name is a correct green. Everything else is a wrong green.

## Metrics

Per photo:
- **legible_count**: number of games marked `legible: true`
- **green_found**: correct greens (green boxes matching a labelled game)
- **wrong_greens**: green boxes not matching any labelled game
- **oranges**: orange box count
- **reds**: red box count
- **cloud_calls**: AI API calls (always 0 until #12)

Aggregate (reported separately for tune and holdout):
- **green_rate**: green_found / legible_count (target: ≥70%)
- **wrong_green_rate**: wrong_greens / total_greens (target: <2%)
- **total photos, boxes, legible games**

## Baseline

A baseline is a JSON snapshot of the aggregate metrics saved in `testset/baselines/`. The eval compares each run against the last accepted baseline.

- `./gradlew :harness:run --args="eval"` runs the eval and prints the report
- `./gradlew :harness:run --args="eval --accept"` saves the current run as the new baseline
- The eval exits non-zero if any metric is worse than baseline

Metrics that must not regress:
- `wrong_greens` must not increase (absolute count)
- `green_rate` must not decrease
- `wrong_green_rate` must not increase

## Definition of done (scan-affecting changes)

Any change that can affect scan results must be checked by running the eval on `testset/` and comparing with the accepted baseline. **Never accept a change that raises wrong greens.** Don't accept one that makes any other metric worse without reporting the trade-off to the user.
