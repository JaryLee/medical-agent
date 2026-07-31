import { describe, expect, it } from "vitest";
import {
  asRecord,
  asRecords,
  asStrings,
  count,
  flag,
  text,
} from "./artifactData";

describe("workspace artifact data guards", () => {
  it("accepts only the expected public DTO shapes", () => {
    const object = { sectionKey: "sec_public", versionNo: 2 };

    expect(asRecord(object)).toBe(object);
    expect(asRecord(null)).toEqual({});
    expect(asRecord(["not-an-object-record"])).toEqual({});
    expect(asRecords([object, null, "invalid"])).toEqual([object, {}, {}]);
    expect(asRecords(object)).toEqual([]);
    expect(asStrings(["PubMed", 7, null, "Crossref"])).toEqual([
      "PubMed",
      "Crossref",
    ]);
  });

  it("fails closed when scalar fields have unexpected types", () => {
    expect(text("科研草案")).toBe("科研草案");
    expect(text(18, "待确认")).toBe("待确认");
    expect(count(18)).toBe(18);
    expect(count("18")).toBe(0);
    expect(flag(true)).toBe(true);
    expect(flag(1)).toBe(false);
  });
});
