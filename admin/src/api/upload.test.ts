// SPDX-License-Identifier: GPL-3.0-or-later

// @vitest-environment node
// jsdom's FormData can't be sent by Node's fetch, so this runs without a DOM; the browser
// sends its own FormData.
import { describe, expect, it } from "vitest";
import { session } from "../auth/session";
import { uploadExtension } from "./extras";

describe("uploadExtension", () => {
  it("posts the file as multipart field 'file' and returns the installed extension", async () => {
    session.signIn("tok");
    const result = await uploadExtension(new File(["fake"], "my-extension.apk"));
    expect(result.extension?.origin?.upload).toBe("my-extension.apk");
    expect(result.extension?.status).toBe("EXTENSION_STATUS_LOADED");
  });

  it("shows the API's message when the file is refused", async () => {
    session.signIn("tok");
    await expect(uploadExtension(new File(["fake"], "notes.txt"))).rejects.toMatchObject({
      status: 400,
      message: "only .jar and .apk files can be installed",
    });
  });
});
