// SPDX-License-Identifier: GPL-3.0-or-later

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";
import type { JsonObject, SearchRequest } from "../api/types";
import { createDb, createHandlers } from "../mocks/handlers";
import { renderApp } from "../test/render";
import { server } from "../test/setup";

describe("sources", () => {
  it("lists sources and filters them by language and name", async () => {
    const app = renderApp("/sources");
    expect(await screen.findByRole("link", { name: "Sample Comics" })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Example Source" })).toHaveLength(2);

    await userEvent.selectOptions(screen.getByLabelText("Language"), "fr");
    await waitFor(() => expect(screen.queryByRole("link", { name: "Sample Comics" })).not.toBeInTheDocument());
    expect(screen.getAllByRole("link", { name: "Example Source" })).toHaveLength(1);

    await userEvent.selectOptions(screen.getByLabelText("Language"), "");
    await userEvent.type(screen.getByLabelText("Name or ID"), "nothing like this");
    expect(await screen.findByText("No source matches the filter.")).toBeInTheDocument();
    app.dispose();
  });

  it("shows an empty state without sources", async () => {
    server.use(http.get("*/v1/sources", () => HttpResponse.json({})));
    const app = renderApp("/sources");
    expect(await screen.findByText(/No sources yet/)).toBeInTheDocument();
    app.dispose();
  });

  it("browses popular with paging, and shows tabs by capability", async () => {
    const app = renderApp("/sources/1001");
    expect(await screen.findByText(/\(Example 1\)$/)).toBeInTheDocument();
    for (const tab of ["Popular", "Latest", "Search", "Settings"]) {
      expect(screen.getByRole("link", { name: tab })).toBeInTheDocument();
    }
    await userEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText(/\(Example 21\)$/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText("No more pages.")).toBeInTheDocument();
    app.dispose();
  });

  it("hides Latest and Settings for sources without them", async () => {
    const app = renderApp("/sources/2001");
    await screen.findByRole("heading", { name: "Sample Comics" });
    expect(screen.queryByRole("link", { name: "Latest" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Settings" })).not.toBeInTheDocument();
    app.dispose();
  });

  it("shows the API's error for a failing source", async () => {
    const app = renderApp("/sources/3001");
    expect(await screen.findByRole("alert")).toHaveTextContent("SOURCE_DOWN: the upstream answered HTTP 503");
    expect(screen.getByText(/needs a browser engine/)).toBeInTheDocument();
    app.dispose();
  });

  it("searches with only the changed filters", async () => {
    let body: SearchRequest | null = null;
    server.use(
      http.post("*/v1/sources/:id/search", async ({ request }) => {
        body = (await request.json()) as SearchRequest;
        return HttpResponse.json({ series: [{ key: "/series/1001-7", title: "Found Example" }], hasNextPage: false });
      }),
    );
    const app = renderApp("/sources/1001?tab=search");
    await userEvent.type(await screen.findByLabelText("Query"), "garden");
    await userEvent.click(await screen.findByLabelText("Completed only"));
    await userEvent.selectOptions(screen.getByLabelText("Status"), "Completed");
    await userEvent.click(screen.getByText("Genres"));
    await userEvent.selectOptions(screen.getByLabelText("Comedy"), "Exclude");
    expect(screen.getByText("(3 changed)")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("Found Example")).toBeInTheDocument();
    expect(body).toEqual({
      page: 1,
      query: "garden",
      filters: [
        { path: [2], checkbox: true },
        { path: [4], select: 2 },
        { path: [6, 1], triState: "TRI_STATE_EXCLUDE" },
      ],
    });
    app.dispose();
  });

  it("saves only changed settings and shows validation errors", async () => {
    let patched: JsonObject | null = null;
    const app = renderApp("/sources/1001?tab=settings");
    const mirror = await screen.findByLabelText("Mirror host");
    expect(screen.getByLabelText("Image quality")).toHaveDisplayValue("High");
    expect(screen.getByLabelText("English")).toBeChecked();

    await userEvent.type(mirror, "http://mirror.example");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("mirror: must start with https://");

    server.events.on("request:start", async ({ request }) => {
      if (request.method === "PATCH") patched = (await request.clone().json()) as JsonObject;
    });
    await userEvent.clear(mirror);
    await userEvent.type(mirror, "https://mirror.example");
    await userEvent.click(screen.getByLabelText("French"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Saved.")).toBeInTheDocument();
    server.events.removeAllListeners();
    expect(patched).toEqual({ values: { mirror: "https://mirror.example", languages: ["en", "fr"] } });
    app.dispose();
  });

  it("shows series details, chapters and a chapter's pages", async () => {
    const app = renderApp("/sources/1001/series?key=%2Fseries%2F1001-4");
    expect(await screen.findByRole("heading", { name: /\(Example 4\)$/ })).toBeInTheDocument();
    expect(screen.getByText("A. Example")).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("link", { name: "Chapter 12" }));
    expect(await screen.findByRole("heading", { name: "Chapter preview" })).toBeInTheDocument();
    expect(await screen.findAllByRole("img", { name: /^Page \d$/ })).toHaveLength(6);
    app.dispose();
  });
});

describe("extensions", () => {
  it("lists extensions with load errors and installs by URL", async () => {
    const app = renderApp("/extensions");
    expect(await screen.findByText(/Unsupported extensions-lib version 1.2/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("URL of a .jar or .apk"), "https://example.com/new-thing.jar");
    await userEvent.click(screen.getByRole("button", { name: "Install" }));
    expect(await screen.findByText(/Installed Installed newthing 1.0.0/)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Installed newthing" })).toBeInTheDocument();
    app.dispose();
  });

  it("shows why a reload failed and uninstalls with data", async () => {
    let url = "";
    server.events.on("request:start", ({ request }) => {
      if (request.method === "DELETE") url = request.url;
    });
    const app = renderApp("/extensions");
    const card = (await screen.findByRole("heading", { name: "Broken Example" })).closest("article") as HTMLElement;
    await userEvent.click(within(card).getByRole("button", { name: "Reload" }));
    expect(await within(card).findByRole("alert")).toHaveTextContent("Reload: Unsupported extensions-lib");
    await userEvent.click(within(card).getByRole("button", { name: "Uninstall" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByLabelText(/delete the sources' settings/));
    await userEvent.click(within(dialog).getByRole("button", { name: "Uninstall" }));
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Broken Example" })).not.toBeInTheDocument());
    server.events.removeAllListeners();
    expect(url).toMatch(/\/v1\/admin\/extensions\/eu\.example\.extension\.broken\?deleteData=true$/);
    app.dispose();
  });
});

describe("stores", () => {
  it("lists stores with their errors", async () => {
    const app = renderApp("/stores");
    expect(await screen.findByRole("heading", { name: /Example Store/ })).toBeInTheDocument();
    expect(screen.getByText(/HTTP 503 from legacy.example.org/)).toBeInTheDocument();
    app.dispose();
  });

  it("shows available extensions with updates and content warnings, and installs", async () => {
    const app = renderApp("/stores?view=available");
    expect(await screen.findByRole("button", { name: "Update" })).toBeInTheDocument();
    const adult = screen.getByText("Adult Example").closest("tr") as HTMLElement;
    expect(within(adult).getByText("NSFW")).toBeInTheDocument();
    expect(
      within(screen.getByText("Old Example").closest("tr") as HTMLElement).getByText("Unsupported lib version"),
    ).toBeInTheDocument();

    const demo = screen.getByText("Demo Reader").closest("tr") as HTMLElement;
    await userEvent.click(within(demo).getByRole("button", { name: "Install" }));
    await waitFor(() => expect(within(demo).getByText("Installed", { selector: "span.muted" })).toBeInTheDocument());

    await userEvent.click(screen.getByLabelText("Updates only"));
    await waitFor(() => expect(screen.queryByText("Demo Reader")).not.toBeInTheDocument());
    expect(screen.getByText("Example Source")).toBeInTheDocument();
    app.dispose();
  });
});

describe("clients", () => {
  it("creates a client and shows its key once", async () => {
    const app = renderApp("/clients");
    await screen.findByText("panelarr-server");
    await userEvent.click(screen.getByRole("button", { name: "New client" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "my-script");
    await userEvent.click(within(dialog).getByLabelText(/sources:write/));
    await userEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    const keyDialog = await screen.findByRole("dialog", { name: "Key for my-script" });
    expect(within(keyDialog).getByText(/won't be shown again/)).toBeInTheDocument();
    expect(within(keyDialog).getByTestId("api-key").textContent).toMatch(/^msk_/);
    await userEvent.click(within(keyDialog).getByRole("button", { name: "I've copied it" }));
    expect(screen.queryByTestId("api-key")).not.toBeInTheDocument();
    expect(await screen.findByText("my-script")).toBeInTheDocument();
    app.dispose();
  });

  it("rotates a key", async () => {
    const app = renderApp("/clients");
    const row = (await screen.findByText("backup-script")).closest("tr") as HTMLElement;
    await userEvent.click(within(row).getByRole("button", { name: "Rotate key" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Rotate key" }));
    expect(await screen.findByRole("dialog", { name: "New key for backup-script" })).toBeInTheDocument();
    app.dispose();
  });
});

describe("integrations", () => {
  it("shows solvers, profiles and where each source's settings come from", async () => {
    const app = renderApp("/integrations");
    expect(await screen.findByText("Main solver")).toBeInTheDocument();
    expect(await screen.findByText("password stored")).toBeInTheDocument();
    expect(await screen.findByText("Pushed by host")).toBeInTheDocument();
    expect(screen.getByText("host-pushed-example")).toBeInTheDocument();
    expect(screen.getAllByText("Assigned").length).toBeGreaterThan(0);
    app.dispose();
  });

  it("keeps the stored proxy password when the field is left empty", async () => {
    let body: JsonObject | null = null;
    server.events.on("request:start", async ({ request }) => {
      if (request.method === "PUT") body = (await request.clone().json()) as JsonObject;
    });
    const app = renderApp("/integrations");
    const row = (await screen.findByText("via-proxy")).closest("tr") as HTMLElement;
    await userEvent.click(within(row).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByPlaceholderText("Leave empty to keep")).toHaveValue("");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    server.events.removeAllListeners();
    expect(body).toMatchObject({
      id: "via-proxy",
      proxy: { url: "socks5://proxy.example:1080", username: "example-user" },
    });
    expect((body as unknown as { proxy: JsonObject }).proxy).not.toHaveProperty("password");
    app.dispose();
  });

  it("flags a solver no profile uses and puts it on the default profile", async () => {
    const db = createDb();
    db.solvers = [
      ...db.solvers,
      { id: "spare", name: "Spare solver", type: "SOLVER_TYPE_BYPARR", url: "http://spare:8191" },
    ];
    server.resetHandlers(...createHandlers(db));
    const app = renderApp("/integrations");
    const row = (await screen.findByText("Spare solver")).closest("tr") as HTMLElement;
    expect(await within(row).findByText("Not used by any profile")).toBeInTheDocument();
    await userEvent.click(within(row).getByRole("button", { name: "Use for all sources" }));
    await waitFor(() => expect(db.profiles.find((p) => p.id === db.defaultProfileId)?.solverId).toBe("spare"));
    await waitFor(() => expect(within(row).queryByText("Not used by any profile")).not.toBeInTheDocument());
    app.dispose();
  });

  it("shows the API's error when a solver is still in use", async () => {
    const app = renderApp("/integrations");
    const row = (await screen.findByText("Main solver")).closest("tr") as HTMLElement;
    await userEvent.click(within(row).getByRole("button", { name: "Delete" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete solver" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("profile via-proxy still uses solver solver-main");
    app.dispose();
  });
});

it("shows a not-found page", async () => {
  const app = renderApp("/no/such/page");
  expect(await screen.findByRole("heading", { name: "Not found" })).toBeInTheDocument();
  app.dispose();
});
