// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { mockProfiles } from "../mocks/data";
import { durationToSeconds, formatDuration, secondsToDuration } from "./duration";
import { formToProfile, parseHeaders, profileToForm } from "./profileForm";

describe("profile form", () => {
  it("round-trips a profile without sending the password back", () => {
    for (const profile of mockProfiles()) {
      const form = profileToForm(profile);
      const body = formToProfile(form);
      expect(body.proxy?.password).toBeUndefined();
      const { proxy, ...rest } = body;
      const { proxy: expectedProxy, ...expectedRest } = profile;
      expect(rest).toEqual({ ...expectedRest, userAgentMode: profile.userAgentMode });
      expect(proxy).toEqual(expectedProxy ? { url: expectedProxy.url, username: expectedProxy.username } : undefined);
    }
  });

  it("shows that a password is stored and sends a new one only when typed", () => {
    const profile = mockProfiles()[1];
    const form = profileToForm(profile);
    expect(form.hasPassword).toBe(true);
    expect(form.proxyPassword).toBe("");
    expect(formToProfile({ ...form, proxyPassword: "new-secret" }).proxy?.password).toBe("new-secret");
  });

  it("leaves the proxy out when its URL is empty", () => {
    const form = { ...profileToForm(mockProfiles()[1]), proxyUrl: "" };
    expect(formToProfile(form).proxy).toBeUndefined();
  });

  it("validates input", () => {
    const form = profileToForm({ id: "ok" });
    expect(() => formToProfile({ ...form, id: "Not OK" })).toThrow(/lowercase/);
    expect(() => formToProfile({ ...form, rateRequests: "5" })).toThrow(/period/);
    expect(() => formToProfile({ ...form, concurrency: "1.5" })).toThrow(/whole number/);
    expect(() => formToProfile({ ...form, userAgentMode: "USER_AGENT_MODE_FIXED" })).toThrow(/fixed user agent/);
    expect(() => formToProfile({ ...form, readTimeout: "-1" })).toThrow(/Read timeout/);
  });

  it("builds the rate limit and timeouts", () => {
    const form = { ...profileToForm({ id: "p" }), rateRequests: "3", ratePeriod: "1.5", connectTimeout: "10" };
    expect(formToProfile(form)).toMatchObject({
      rateLimit: { requests: 3, period: "1.5s", perHost: false },
      connectTimeout: "10s",
    });
  });
});

describe("parseHeaders", () => {
  it("parses Name: value lines", () => {
    expect(parseHeaders("Accept-Language: en\n\nX-Test:  a:b ")).toEqual({ "Accept-Language": "en", "X-Test": "a:b" });
    expect(() => parseHeaders("no colon here")).toThrow(/line 1/);
  });
});

describe("durations", () => {
  it("converts protobuf durations", () => {
    expect(durationToSeconds("1.5s")).toBe(1.5);
    expect(durationToSeconds("")).toBeUndefined();
    expect(secondsToDuration("30")).toBe("30s");
    expect(secondsToDuration(" ")).toBeUndefined();
    expect(formatDuration("1800s")).toBe("30m");
    expect(formatDuration("90s")).toBe("1m 30s");
    expect(formatDuration(undefined)).toBe("—");
  });
});
