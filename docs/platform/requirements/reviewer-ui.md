# Reviewer page

A small web page served by the controller, so that a person can start a run, answer the agent's questions and watch the run go from the requirement to the generated code, without Postman. It only calls the controller's existing HTTP operations; it reads nothing from the database.

## UI-1 Follow a run in one page

Status: open

As a requester, I want one page where I can start a run, answer its questions and see each stage's result as it appears, so that I can follow and judge the run without reading JSON.

- AC-UI-1.1 The controller serves one page at `/` made of plain HTML, CSS and JavaScript files, with no framework, no build step and no outside resource (no CDN, no web font). The page is readable on a phone and on a desktop, and follows the light or dark setting of the browser.
- AC-UI-1.2 The page has a box for the requirement and a button that posts it to `POST /v1/runs`, then follows the new run. The id of the run is kept in the page address, so a reload or a pasted link resumes following it.
- AC-UI-1.3 The page polls `GET /v1/runs/{id}` every two seconds while the run is not finished, and shows the status, the failure reason when failed, and the eight stages with their state (not started, running, finished). It stops polling when the run is SUCCEEDED or FAILED.
- AC-UI-1.4 While the run is `WAITING_FOR_INPUT`, the page reads `GET /v1/runs/{id}/questions`, shows each question with its text, why it matters and any proposed default, one answer field per question, and a button that posts all answers together to `POST /v1/runs/{id}/replies`. After an accepted reply it goes back to following the run.
- AC-UI-1.5 When the requirements artifact exists, the page shows its stories as bullets: id and title, "as a ... I want ... so that ...", and the acceptance criteria as nested bullets, followed by the assumptions as bullets.
- AC-UI-1.6 When the design and plan artifacts exist, the page shows them as readable bullets: components, interfaces, decisions, alternatives and risks for the design (diagram text in a preformatted block), and the tasks for the plan. A value that is a text shows as a bullet, and a value that is an object shows its fields as nested bullets.
- AC-UI-1.7 While the development stage is running the page shows a "Building..." indicator. When the development artifact exists it lists each generated file with its path and its code in a preformatted block with a copy button, and, once there is a verification result, shows its outcome and the number of tests run, passed and failed, and the commit id.
- AC-UI-1.8 The QA, review and documentation results are shown when they exist: a short readable section for each, built from the artifact content in the same bullet form.
- AC-UI-1.9 Text that comes from the run or a model is always shown as text and never as HTML, so that nothing a model writes can run in the page.
- AC-UI-1.10 A test shows that `GET /` answers 200 with an HTML page that loads the page's own script and style files, and that those files are served too.

## UI-2 Ask for a change on a finished run

Status: coded

As a requester, I want to type a change request under a finished run and watch the change get built on top of it, so that I can keep improving a product in one conversation.

- AC-UI-2.1 When the run being followed is SUCCEEDED and has a commit, the conversation ends with a "Request a change" box: a text area and a button. Sending it posts `{requirement, kind: "BROWNFIELD", baseRunId: <this run's id>}` to `POST /v1/runs` and then follows the new run in the same conversation view, without a reload.
- AC-UI-2.2 The box is not shown for a run that is not SUCCEEDED, has no commit, or is still being followed. A refusal from the server (a 400) shows its message under the box and keeps what was typed.
- AC-UI-2.3 The view of a change run says which run it changes ("Change to run <first 8 characters>") with a link that opens the base run, and the base run's view offers the change runs this page started, as links, from the browser's own memory. Both are optional text when nothing is known.
- AC-UI-2.4 The existing "Change an existing run" choice in the start box keeps working.
- AC-UI-2.5 Everything is built with `createElement` and `textContent` only. The behaviour is checked by hand.

## UI-3 A chat bar pinned at the bottom, and approvals

Status: open

As a requester, I want a chat bar that is always at the bottom of the page and the approve and reject buttons when the run asks for them, so that I can steer a run from the conversation.

- AC-UI-3.1 While a run is followed, a bar is pinned to the bottom of the viewport with a text area and a Send button. When the run is SUCCEEDED and has a commit, Send posts `{requirement, kind: "BROWNFIELD", baseRunId: <this run>}` and follows the new run (this replaces the "Request a change" card of UI-2.1; the base-link lines of UI-2.3 stay). In every other state the bar is shown disabled with a one-line reason (still running; waiting for your answers; waiting for your approval; failed, use Retry).
- AC-UI-3.2 The bar never covers the conversation: the page leaves room for it and keeps the reader's scroll position, on a phone width too.
- AC-UI-3.3 When `GET /v1/runs/{id}` has `approval` (`kind`, `version`, `fingerprint`, `summary`, `rejectionsLeft`), an approval card shows what waits for approval, a link that opens that artifact, a name field (remembered in the browser's storage, optional storage), a comment field, and Approve and Reject buttons. Reject needs a comment. The buttons post to `POST /v1/runs/{id}/decisions` with `{decision, approver, comment}`; a 400 or 409 shows its message in the card and keeps the text; after a 202 the page goes back to following the run.
- AC-UI-3.4 Everything is built with `createElement` and `textContent` only. The behaviour is checked by hand.

