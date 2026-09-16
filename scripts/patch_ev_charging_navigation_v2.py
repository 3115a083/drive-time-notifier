from pathlib import Path
import runpy

path = Path(__file__).with_name("patch_ev_charging_navigation.py")
text = path.read_text(encoding="utf-8")
multiline = '''                    OsmEnrichmentClient().query(
                        points,
                        settings.showSpeedCameras,
                        settings.showParking,
                        ChargingSearchOptions.from(settings)
                    )'''
oneline = '''                    OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking, ChargingSearchOptions.from(settings))'''

for marker in ("# Single-event worker.", "# Next-day worker."):
    start = text.index(marker)
    end = text.find("# ", start + len(marker))
    if end < 0:
        end = len(text)
    segment = text[start:end]
    if multiline not in segment:
        raise SystemExit(f"Expected query target after {marker}")
    segment = segment.replace(multiline, oneline, 1)
    text = text[:start] + segment + text[end:]

path.write_text(text, encoding="utf-8")
runpy.run_path(str(path), run_name="__main__")
