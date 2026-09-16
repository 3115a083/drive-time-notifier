from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
for rel in (
    "app/src/main/java/de/drivetime/notifier/automation/SingleEventWorker.kt",
    "app/src/main/java/de/drivetime/notifier/automation/NextDayWorker.kt",
):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    target = "import de.drivetime.notifier.routing.ChargingSearchOptions\n"
    needed = "import de.drivetime.notifier.routing.ChargingRoutePlanner\n"
    if needed not in text:
        if target not in text:
            raise SystemExit(f"Missing charging import anchor: {rel}")
        text = text.replace(target, needed + target, 1)
        path.write_text(text, encoding="utf-8")

runpy.run_path(str(Path(__file__).with_name("patch_ev_charging_navigation_v2.py")), run_name="__main__")
