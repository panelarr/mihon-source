// SPDX-License-Identifier: GPL-3.0-or-later

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";
import { createDb, createHandlers } from "../../mocks/handlers";
import { renderApp } from "../../test/render";
import { server } from "../../test/setup";

function row(text: string): HTMLElement {
  return screen.getByRole("link", { name: text }).closest("tr") as HTMLElement;
}

describe("jobs", () => {
  it("lists jobs with their schedule, last run and next run", async () => {
    const app = renderApp("/jobs");
    await screen.findByRole("link", { name: "Refresh stores" });
    const refresh = row("Refresh stores");
    expect(within(refresh).getByText("Every 12h")).toBeInTheDocument();
    expect(within(refresh).getByText("MIHON_STORE_REFRESH_INTERVAL")).toBeInTheDocument();
    expect(within(refresh).getByText("Failed")).toBeInTheDocument();
    expect(within(refresh).getByText(/1 of 2 stores failed/)).toBeInTheDocument();

    const health = row("Check sources");
    expect(within(health).getByText("Manual only")).toBeInTheDocument();
    expect(within(health).getByText("Never ran")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Sources" })).toBeInTheDocument();
    app.dispose();
  });

  it("runs a job with params, shows its progress and cancels it", async () => {
    const db = createDb();
    server.resetHandlers(...createHandlers(db));
    const app = renderApp("/jobs");
    await screen.findByRole("link", { name: "Check sources" });
    await userEvent.click(within(row("Check sources")).getByRole("button", { name: "Run now" }));

    const dialog = await screen.findByRole("dialog");
    // The default is ticked; add another step.
    expect(within(dialog).getByRole("checkbox", { name: "popular" })).toBeChecked();
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "latest" }));
    await userEvent.type(within(dialog).getByLabelText(/source ids/), "1001, 2001");
    await userEvent.click(within(dialog).getByRole("button", { name: "Run now" }));

    expect(await screen.findByRole("heading", { name: /^Run mock/ })).toBeInTheDocument();
    expect(await screen.findByText("Running")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Progress" })).toBeInTheDocument();
    expect(db.runs[0]?.params).toEqual({ steps: "popular,latest", source_ids: "1001, 2001" });

    await userEvent.click(screen.getByRole("button", { name: "Cancel run" }));
    expect(await screen.findByText("Cancelled", { selector: ".badge" })).toBeInTheDocument();
    app.dispose();
  });

  it("links the current run when the job is already running", async () => {
    server.use(
      http.post(/\/v1\/admin\/jobs\/([^/]+):run$/, () =>
        HttpResponse.json(
          {
            code: "ALREADY_EXISTS",
            reason: "ALREADY_RUNNING",
            message: "already running",
            currentRunId: "0190a0a1b2c3d4",
          },
          { status: 409 },
        ),
      ),
    );
    const app = renderApp("/jobs/store-refresh");
    await userEvent.click(await screen.findByRole("button", { name: "Run now" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Run now" }));
    expect(await within(dialog).findByText(/already running/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("link", { name: "Open the current run" }));
    expect(await screen.findByRole("heading", { name: "Run 0190a0a1b2c3d4" })).toBeInTheDocument();
    app.dispose();
  });

  it("shows a job's runs, filtered by status", async () => {
    const app = renderApp("/jobs/store-refresh");
    expect(await screen.findByRole("heading", { name: "Refresh stores" })).toBeInTheDocument();
    expect(await screen.findByText("Refreshed 2 stores; 1 update available")).toBeInTheDocument();
    expect(screen.getByText(/1 of 2 stores failed/)).toBeInTheDocument();
    expect(screen.getByText("Manual (admin)")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Status"), "JOB_RUN_STATUS_FAILED");
    await waitFor(() => expect(screen.queryByText("Refreshed 2 stores; 1 update available")).not.toBeInTheDocument());
    expect(screen.getByText(/1 of 2 stores failed/)).toBeInTheDocument();
    app.dispose();
  });

  it("shows a finished run with counts and notes", async () => {
    const app = renderApp("/job-runs/0190a1b2c3d4e5");
    expect(await screen.findByRole("heading", { name: "Run 0190a1b2c3d4e5" })).toBeInTheDocument();
    expect(await screen.findByText("failed: 1")).toBeInTheDocument();
    expect(screen.getByText(/Legacy Example Store: Fetching the index failed/)).toBeInTheDocument();
    expect(screen.getByText("legacy-example")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel run" })).not.toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Refresh stores" })).toBeInTheDocument();
    app.dispose();
  });
});
