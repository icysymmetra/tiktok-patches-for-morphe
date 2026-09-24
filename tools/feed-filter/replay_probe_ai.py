#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

REF = re.compile(r"^(@\d+)")
TOKEN = re.compile(r"\btoken=([0-9a-f]+)")


def field(fields, suffix):
    for name, value in fields.items():
        if name.endswith(suffix):
            return value.get("value", "")
    return ""


def integer(value):
    match = re.search(r"Integer:([-+]?\d+)", value)
    return int(match.group(1)) if match else 0


def boolean(value):
    return value.endswith(":true")


def reference(value):
    match = REF.match(value)
    return match.group(1) if match else None


def extract(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    items = {}
    for stages in data.get("snapshots", {}).values():
        for stage in stages:
            objects = stage.get("objects", {})
            for obj in objects.values():
                if obj.get("class") != "com.ss.android.ugc.aweme.feed.model.Aweme":
                    continue
                fields = obj.get("fields", {})
                aid = field(fields, ".Aweme.aid")
                token_match = TOKEN.search(aid)
                if not token_match:
                    continue
                token = token_match.group(1)

                label_type = 0
                created = False
                aigc_ref = reference(field(fields, ".Aweme.aigcInfo"))
                if aigc_ref and aigc_ref in objects:
                    aigc_fields = objects[aigc_ref].get("fields", {})
                    label_type = integer(field(aigc_fields, ".AIGCInfo.AIGCLabelType"))
                    created = boolean(field(aigc_fields, ".AIGCInfo.createByAI"))

                moderation_present = False
                moderation_type = 0
                moderation_ref = reference(field(fields, ".Aweme.moderationAigcInfo"))
                if moderation_ref and moderation_ref in objects:
                    moderation_present = True
                    moderation_fields = objects[moderation_ref].get("fields", {})
                    moderation_type = integer(
                        field(moderation_fields, ".ModerationAigcInfo.moderationAigcLabelType")
                    )

                previous = items.get(token, (0, False, False, 0))
                items[token] = (
                    label_type if label_type != 0 else previous[0],
                    created or previous[1],
                    moderation_present or previous[2],
                    moderation_type if moderation_type != 0 else previous[3],
                )
    return items


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    items = extract(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        output.write("token\tlabelType\tcreatedByAi\tmoderationPresent\tmoderationLabelType\n")
        for token, values in sorted(items.items()):
            output.write(
                f"{token}\t{values[0]}\t{str(values[1]).lower()}\t"
                f"{str(values[2]).lower()}\t{values[3]}\n"
            )
    print(f"{args.input.name}: extracted {len(items)} unique items")


if __name__ == "__main__":
    main()
