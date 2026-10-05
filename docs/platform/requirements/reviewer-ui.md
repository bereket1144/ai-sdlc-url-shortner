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
