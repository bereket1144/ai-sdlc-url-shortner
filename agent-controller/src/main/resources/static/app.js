// Reviewer page: starts a run, answers its questions and shows each stage's result as it appears.
// It only calls the controller's own HTTP operations.
//
// AC-UI-1.9: text that comes from a run or a model is put on the page only through createElement, textContent and
// Text nodes (el() below). This file never builds HTML from a string.
(() => {
  'use strict';

  const POLL_MS = 2000;
  const MAX_RECENT = 5;
  const RECENT_KEY = 'recentRuns';
  const RUN_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const END_STATUSES = ['SUCCEEDED', 'FAILED'];

  const STAGE_LABELS = {
    requirements: 'Requirements', impactAnalysis: 'Impact analysis', design: 'Design', planning: 'Planning',
    development: 'Development', qa: 'QA', review: 'Review', documentation: 'Documentation'
  };
  const STATE_LABELS = { NOT_STARTED: 'Not started', RUNNING: 'Running', FINISHED: 'Finished' };
  const STATUS_TONES = { CREATED: 'neutral', RUNNING: 'info', WAITING_FOR_INPUT: 'warn', SUCCEEDED: 'good', FAILED: 'bad' };
  const OUTCOME_TONES = { PASSED: 'good', FAILED: 'bad', NO_RESULT: 'neutral' };
  const FIELD_TITLES = { changes: 'Files' };

  // One card per artifact kind, in the order they are shown. "fields" is the order of the fields in the card (the
  // stored JSON does not keep key order); "custom" names the few fields that have their own view. Every other field
  // is shown as bullets by the one generic renderer.
  const SECTIONS = [
    { kind: 'requirements', title: 'Requirements', fields: ['stories', 'assumptions'],
      custom: { stories: storiesView }, ready: content => content.stories !== undefined },
    { kind: 'design', title: 'Design',
      fields: ['components', 'interfaces', 'dataFlow', 'decisions', 'alternatives', 'risks', 'diagrams'],
      custom: { diagrams: diagramsView } },
    { kind: 'plan', title: 'Plan', fields: ['tasks', 'assumptions'] },
    { kind: 'development', title: 'Generated code',
      fields: ['rationale', 'changes', 'affectedCriteria', 'errorHandling', 'verificationNotes'],
      custom: { changes: filesView } },
    { kind: 'qa', title: 'QA',
      fields: ['proposedTests', 'criterionMapping', 'observedEvidence', 'coverageGaps', 'failureDetails'] },
    { kind: 'review', title: 'Review', fields: ['reviewedFiles', 'findings', 'evidence', 'resolutionStatus'] },
    { kind: 'documentation', title: 'Documentation',
      fields: ['setup', 'usage', 'architecture', 'examples', 'knownLimits', 'evidenceLinks'] }
  ];

  // ---- Building elements -----------------------------------------------------------------------------------

  // The only way text reaches the page: string children become Text nodes and "text" sets textContent.
  // Attribute names are always written in this file, never taken from data.
  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(props)) {
      if (name === 'class') {
        node.className = value;
      } else if (name === 'text') {
        node.textContent = value;
      } else {
        node.setAttribute(name, value);
      }
    }
    node.append(...children);
    return node;
  }

  function setText(node, value) {
    if (node.textContent !== value) {
      node.textContent = value;
    }
  }

  function setData(node, name, value) {
    if (node.dataset[name] !== value) {
      node.dataset[name] = value;
    }
  }

  function setMessage(node, message) {
    node.textContent = message;
    node.hidden = message === '';
  }

  function humanize(key) {
    const spaced = String(key).replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ');
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  function asText(value) {
    if (typeof value === 'string') {
      return value;
    }
    if (value === undefined || value === null) {
      return '';
    }
    return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
  }

  // ---- The one renderer: any JSON value as nested bullets ---------------------------------------------------

  function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function isEmpty(value) {
    return value === null || value === undefined || value === ''
      || (Array.isArray(value) && value.length === 0)
      || (isObject(value) && Object.keys(value).length === 0);
  }

  // A text is a bullet, an object is bullets of its fields, an array is a bullet for each item.
  function bullets(value) {
    if (isEmpty(value)) {
      return el('p', { class: 'muted', text: 'None' });
    }
    const list = el('ul', { class: 'tree' });
    addItems(list, value);
    return list;
  }

  function addItems(list, value) {
    if (Array.isArray(value)) {
      value.forEach(item => list.append(bullet(null, item)));
    } else if (isObject(value)) {
      Object.entries(value).forEach(([key, field]) => list.append(bullet(key, field)));
    } else {
      list.append(bullet(null, value));
    }
  }

  function bullet(key, value) {
    const item = el('li');
    if (key !== null) {
      item.append(el('span', { class: 'key', text: humanize(key) }));
    }
    if (isEmpty(value)) {
      item.append(el('span', { class: 'muted', text: 'none' }));
    } else if (typeof value !== 'object') {
      item.append(el('span', { class: 'text', text: String(value) }));
    } else {
      const nested = el('ul', { class: 'tree' });
      addItems(nested, value);
      item.append(nested);
      if (key === null && isObject(value)) {
        item.classList.add('group');
      }
    }
    return item;
  }

  // ---- Dedicated views ---------------------------------------------------------------------------------------

  function storiesView(stories) {
    const list = el('ul', { class: 'tree stories' });
    for (const story of stories) {
      const criteria = el('ul', { class: 'tree' });
      for (const criterion of story.acceptanceCriteria || []) {
        criteria.append(el('li', {}, el('span', { class: 'id', text: asText(criterion.id) }), asText(criterion.text)));
      }
      list.append(el('li', {},
        el('span', { class: 'story-head' },
          el('span', { class: 'id', text: asText(story.id) }), el('strong', { text: asText(story.title) })),
        el('ul', { class: 'tree' },
          el('li', { text: `As a ${asText(story.role)}, I want ${asText(story.want)}, so that ${asText(story.benefit)}` }),
          el('li', {}, el('span', { class: 'key', text: 'Acceptance criteria' }), criteria))));
    }
    return list;
  }

  function diagramsView(diagrams) {
    const wrap = el('div', { class: 'diagrams' });
    for (const diagram of [].concat(diagrams)) {
      wrap.append(el('pre', { class: 'code' }, el('code', { text: asText(diagram) })));
    }
    return wrap;
  }

  function filesView(changes) {
    const wrap = el('div', { class: 'files' });
    for (const change of changes) {
      const code = asText(change.content);
      const copy = el('button', { type: 'button', class: 'btn small', text: 'Copy' });
      copy.addEventListener('click', () => copyText(code, copy));
      wrap.append(el('div', { class: 'file' },
        el('div', { class: 'file-head' }, el('span', { class: 'mono', text: asText(change.path) }), copy),
        el('pre', { class: 'code' }, el('code', { text: code }))));
    }
    return wrap;
  }

  async function copyText(value, button) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        copyWithoutClipboardApi(value);
      }
      flash(button, 'Copied');
    } catch (error) {
      console.error('event=copy_failed', error);
      flash(button, 'Copy failed');
    }
  }

  // The clipboard API exists only on a secure page (https or localhost); a page opened by network address needs this.
  function copyWithoutClipboardApi(value) {
    const area = el('textarea', { class: 'offscreen', readonly: '' });
    area.value = value;
    document.body.append(area);
    area.select();
    const copied = document.execCommand('copy');
    area.remove();
    if (!copied) {
      throw new Error('The browser refused to copy.');
    }
  }

  function flash(button, label) {
    button.textContent = label;
    setTimeout(() => { button.textContent = 'Copy'; }, 1500);
  }

  function renderFields(section, content) {
    // A known field is shown when it is there, even when empty (it says "None"). A field this page does not know is
    // shown too, but only when it holds something.
    const known = section.fields.filter(name => name in content);
    const extra = Object.keys(content).filter(name => !section.fields.includes(name) && !isEmpty(content[name]));
    const fragment = document.createDocumentFragment();
    for (const name of [...known, ...extra]) {
      const value = content[name];
      const view = !isEmpty(value) && section.custom && section.custom[name] ? section.custom[name] : bullets;
      fragment.append(el('div', { class: 'field' }, el('h3', { text: FIELD_TITLES[name] || humanize(name) }), view(value)));
    }
    return fragment;
  }

  // ---- The controller's operations ---------------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, message) {
      super(message);
      this.status = status;
    }
  }

  async function api(path, options = {}) {
    let response;
    try {
      response = await fetch(path, { cache: 'no-store', ...options });
    } catch (error) {
      throw new ApiError(0, 'Could not reach the controller.');
    }
    let body = null;
    try {
      body = await response.json();
    } catch (error) {
      // An empty or unreadable body is judged below, with the status.
    }
    if (!response.ok) {
      throw new ApiError(response.status, problemText(body, response.status));
    }
    if (body === null) {
      throw new ApiError(response.status, 'The controller sent an answer the page cannot read.');
    }
    return body;
  }

  // The controller's errors are problem details: a "detail", and for a refused field an "errors" list.
  function problemText(body, status) {
    if (!isObject(body)) {
      return `The request failed (${status}).`;
    }
    const fields = Array.isArray(body.errors)
      ? body.errors.map(error => `${asText(error.field)}: ${asText(error.message)}`).join('; ') : '';
    const text = [body.detail || body.title, fields].filter(Boolean).join(' ');
    return text || `The request failed (${status}).`;
  }

  function postJson(body) {
    return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  }

  // ---- The page ----------------------------------------------------------------------------------------------

  const $ = id => document.getElementById(id);

  const ui = {
    start: $('start'), startForm: $('start-form'), requirement: $('requirement'), startButton: $('start-button'),
    startMessage: $('start-message'), recent: $('recent'), recentList: $('recent-list'),
    run: $('run'), runId: $('run-id'), runStatus: $('run-status'), notice: $('notice'),
    failure: $('failure'), failureCode: $('failure-code'), failureMessage: $('failure-message'),
    stages: $('stages'), questionsCard: $('questions-card'), questionsForm: $('questions-form'),
    questionList: $('question-list'), questionsMessage: $('questions-message'), questionsSend: $('questions-send'),
    sections: $('sections'),
    building: el('p', { class: 'building', role: 'status', hidden: '' }, 'Building',
      el('span', { class: 'dots', 'aria-hidden': 'true' },
        el('span', { text: '.' }), el('span', { text: '.' }), el('span', { text: '.' }))),
    verification: el('div', { class: 'verification', hidden: '' })
  };

  const cards = SECTIONS.map(section => {
    const badge = el('span', { class: 'muted small' });
    const body = el('div', { class: 'card-body' });
    const extras = section.kind === 'development' ? [ui.building, ui.verification] : [];
    const root = el('section', { class: 'card', hidden: '' },
      el('div', { class: 'card-head' }, el('h2', { text: section.title }), badge), ...extras, body);
    ui.sections.append(root);
    return { section, root, body, badge };
  });

  // The run being followed, or null. Every answer from the controller is dropped when it is no longer the one
  // followed (the person started or opened another run while it was on its way).
  let current = null;

  function showStart() {
    document.title = 'Run viewer';
    setMessage(ui.startMessage, '');
    ui.start.hidden = false;
    ui.run.hidden = true;
    renderRecent();
  }

  function route() {
    stop();
    const id = new URLSearchParams(location.hash.slice(1)).get('run');
    if (id === null) {
      showStart();
    } else if (RUN_ID.test(id)) {
      follow(id);
    } else {
      showStart();
      setMessage(ui.startMessage, 'That address does not hold a run id.');
    }
  }

  function stop() {
    if (current) {
      clearTimeout(current.timer);
      current = null;
    }
  }

  function follow(id) {
    resetRunView();
    current = {
      id, timer: null, busy: false, shown: new Map(), stageItems: new Map(), answered: new Set(),
      questionKey: null, questionFields: [], verificationSignature: null
    };
    setText(ui.runId, id);
    ui.start.hidden = true;
    ui.run.hidden = false;
    document.title = `Run ${id.slice(0, 8)} - Run viewer`;
    poll(current);
  }

  function resetRunView() {
    ui.stages.replaceChildren();
    ui.questionList.replaceChildren();
    ui.verification.replaceChildren();
    ui.verification.hidden = true;
    ui.building.hidden = true;
    ui.questionsCard.hidden = true;
    ui.failure.hidden = true;
    ui.notice.hidden = true;
    setMessage(ui.questionsMessage, '');
    setText(ui.runStatus, '');
    for (const card of cards) {
      card.root.hidden = true;
      card.body.replaceChildren();
      card.badge.textContent = '';
    }
  }

  function schedule(session, delay) {
    clearTimeout(session.timer);
    session.timer = setTimeout(() => poll(session), delay);
  }

  // AC-UI-1.3: read the run every two seconds until it is SUCCEEDED or FAILED.
  async function poll(session) {
    if (session !== current || session.busy) {
      return;
    }
    session.busy = true;
    let more = true;
    try {
      const run = await api(`/v1/runs/${session.id}`);
      if (session !== current) {
        return;
      }
      ui.notice.hidden = true;
      showRun(session, run);
      await syncQuestions(session, run);
      await syncArtifacts(session, run);
      more = !END_STATUSES.includes(run.status);
    } catch (error) {
      if (session !== current) {
        return;
      }
      console.error(`event=poll_failed runId=${session.id} status=${error.status}`, error);
      // An id the controller refuses or does not know will never work, so the page stops asking.
      more = error.status !== 400 && error.status !== 404;
      showNotice(more ? `${error.message} Trying again.` : error.message, more ? 'warn' : 'bad');
    } finally {
      session.busy = false;
    }
    if (more && session === current) {
      schedule(session, POLL_MS);
    }
  }

  function showNotice(message, tone) {
    ui.notice.textContent = message;
    ui.notice.dataset.tone = tone;
    ui.notice.hidden = false;
  }

  // Status, failure reason, the eight stages, the build indicator and the verification result.
  function showRun(session, run) {
    setText(ui.runStatus, humanize(run.status.toLowerCase()));
    setData(ui.runStatus, 'tone', STATUS_TONES[run.status] || 'neutral');

    const failed = run.status === 'FAILED' && isObject(run.failureReason);
    ui.failure.hidden = !failed;
    if (failed) {
      setText(ui.failureCode, asText(run.failureReason.code));
      setText(ui.failureMessage, asText(run.failureReason.message));
    }

    if (run.stages.some(stage => !session.stageItems.has(stage.stage))) {
      buildStepper(session, run.stages);
    }
    for (const stage of run.stages) {
      const parts = session.stageItems.get(stage.stage);
      setData(parts.item, 'state', stage.state);
      setText(parts.state, STATE_LABELS[stage.state] || humanize(stage.state.toLowerCase()));
    }

    // AC-UI-1.7: "Building..." while the development stage runs, and the result of the build once there is one.
    const development = run.stages.find(stage => stage.stage === 'development');
    ui.building.hidden = !(development && development.state === 'RUNNING');
    showVerification(session, run);
    if (!ui.building.hidden || run.verification) {
      cards.find(card => card.section.kind === 'development').root.hidden = false;
    }
  }

  function buildStepper(session, stages) {
    session.stageItems.clear();
    ui.stages.replaceChildren();
    stages.forEach((stage, index) => {
      const state = el('span', { class: 'stage-state' });
      const item = el('li', { class: 'stage' },
        el('span', { class: 'marker', 'aria-hidden': 'true', text: String(index + 1) }),
        el('span', { class: 'stage-text' },
          el('span', { class: 'stage-name', text: STAGE_LABELS[stage.stage] || humanize(stage.stage) }), state));
      ui.stages.append(item);
      session.stageItems.set(stage.stage, { item, state });
    });
  }

  function showVerification(session, run) {
    const signature = JSON.stringify([run.verification || null, run.commit || null]);
    if (signature === session.verificationSignature) {
      return;
    }
    session.verificationSignature = signature;
    ui.verification.replaceChildren();
    ui.verification.hidden = !run.verification;
    if (!run.verification) {
      return;
    }
    const result = run.verification;
    ui.verification.append(
      el('div', { class: 'stat' },
        el('span', { class: 'stat-label', text: 'Build and tests' }),
        el('span', { class: 'pill', 'data-tone': OUTCOME_TONES[result.outcome] || 'neutral',
          text: humanize(asText(result.outcome).toLowerCase()) })),
      stat('Tests run', result.testsRun), stat('Passed', result.testsPassed), stat('Failed', result.testsFailed));
    if (run.commit) {
      ui.verification.append(el('div', { class: 'commit' },
        el('span', { class: 'stat-label', text: 'Commit' }), el('code', { class: 'mono', text: run.commit })));
    }
  }

  function stat(label, value) {
    const number = el('span', { class: 'stat-value', text: String(value) });
    if (label === 'Failed' && value > 0) {
      number.dataset.tone = 'bad';
    }
    return el('div', { class: 'stat' }, el('span', { class: 'stat-label', text: label }), number);
  }

  // ---- Artifacts ---------------------------------------------------------------------------------------------

  // AC-UI-1.5 to 1.8: each artifact is read once per kind and version; a newer version replaces what is shown.
  async function syncArtifacts(session, run) {
    const latest = new Map();
    for (const artifact of run.artifacts || []) {
      if (!(latest.get(artifact.kind) >= artifact.version)) {
        latest.set(artifact.kind, artifact.version);
      }
    }
    for (const card of cards) {
      const version = latest.get(card.section.kind);
      if (version === undefined || session.shown.get(card.section.kind) === version) {
        continue;
      }
      let content;
      try {
        content = await api(`/v1/runs/${session.id}/artifacts/${encodeURIComponent(card.section.kind)}/${version}`);
      } catch (error) {
        if (session !== current) {
          return;
        }
        // The version stays unmarked, so a run that is still going tries again on the next poll.
        console.error(`event=artifact_failed runId=${session.id} kind=${card.section.kind} version=${version}`, error);
        showNotice(`Could not read the ${card.section.title.toLowerCase()} (${error.message}).`, 'warn');
        continue;
      }
      if (session !== current) {
        return;
      }
      session.shown.set(card.section.kind, version);
      showArtifact(card, version, content);
    }
  }

  function showArtifact(card, version, content) {
    // The first version of the requirements may be the batch of questions, which has its own form.
    if (card.section.ready && !card.section.ready(content)) {
      return;
    }
    let rendered;
    try {
      rendered = renderFields(card.section, content);
    } catch (error) {
      // Whatever a model wrote, the page still shows it, as plain bullets.
      console.error(`event=render_fallback kind=${card.section.kind} version=${version}`, error);
      rendered = bullets(content);
    }
    card.body.replaceChildren(rendered);
    card.badge.textContent = `version ${version}`;
    card.root.hidden = false;
  }

  // ---- Questions (AC-UI-1.4) ---------------------------------------------------------------------------------

  async function syncQuestions(session, run) {
    const key = run.awaiting ? run.awaiting.artifactVersion : 0;
    if (run.status !== 'WAITING_FOR_INPUT' || session.answered.has(key)) {
      ui.questionsCard.hidden = true;
      return;
    }
    if (session.questionKey !== key) {
      const body = await api(`/v1/runs/${session.id}/questions`);
      if (session !== current) {
        return;
      }
      const open = body.questions.filter(question => question.status === 'OPEN'
        && (!run.awaiting || question.artifactVersion === run.awaiting.artifactVersion));
      if (open.length === 0) {
        return; // the questions are not there yet: ask again on the next poll
      }
      buildQuestionForm(session, key, open);
    }
    ui.questionsCard.hidden = false;
  }

  function buildQuestionForm(session, key, questions) {
    session.questionKey = key;
    session.questionFields = [];
    setMessage(ui.questionsMessage, '');
    ui.questionList.replaceChildren();
    for (const question of questions) {
      const input = el('textarea', { id: `answer-${question.id}`, rows: '2', required: '' });
      const block = el('div', { class: 'question' },
        el('label', { for: input.id }, el('span', { class: 'id', text: question.id }), asText(question.text)),
        el('p', { class: 'why', text: `Why it matters: ${asText(question.why)}` }),
        input);
      if (question.proposedDefault) {
        const use = el('button', { type: 'button', class: 'btn small', text: 'Use this answer' });
        use.addEventListener('click', () => {
          input.value = question.proposedDefault;
          input.focus();
        });
        block.append(el('div', { class: 'default-row' },
          el('span', { text: `Proposed default: ${question.proposedDefault}` }), use));
      }
      ui.questionList.append(block);
      session.questionFields.push({ id: question.id, input });
    }
  }

  ui.questionsForm.addEventListener('submit', async event => {
    event.preventDefault();
    const session = current;
    if (!session || session.questionFields.length === 0) {
      return;
    }
    const answers = session.questionFields.map(field => ({ questionId: field.id, answer: field.input.value.trim() }));
    if (answers.some(answer => answer.answer === '')) {
      setMessage(ui.questionsMessage, 'Please answer every question.');
      return;
    }
    setMessage(ui.questionsMessage, '');
    ui.questionsSend.disabled = true;
    try {
      await api(`/v1/runs/${session.id}/replies`, postJson({ answers }));
      if (session !== current) {
        return;
      }
      session.answered.add(session.questionKey);
      session.questionFields = [];
      ui.questionList.replaceChildren();
      ui.questionsCard.hidden = true;
      schedule(session, 0);
    } catch (error) {
      if (session !== current) {
        return;
      }
      console.error(`event=reply_failed runId=${session.id} status=${error.status}`, error);
      setMessage(ui.questionsMessage, error.message);
      if (error.status === 409) {
        schedule(session, 0); // the run is no longer waiting: read it again
      }
    } finally {
      ui.questionsSend.disabled = false;
    }
  });

  // ---- Starting a run (AC-UI-1.2) ----------------------------------------------------------------------------

  ui.startForm.addEventListener('submit', async event => {
    event.preventDefault();
    const requirement = ui.requirement.value;
    setMessage(ui.startMessage, '');
    ui.startButton.disabled = true;
    try {
      const created = await api('/v1/runs', postJson({ requirement }));
      remember(created.id, requirement);
      ui.requirement.value = '';
      // The id in the address is what a reload or a pasted link follows; the change of address starts the following.
      location.hash = `run=${created.id}`;
    } catch (error) {
      console.error(`event=start_failed status=${error.status}`, error);
      setMessage(ui.startMessage, error.message);
    } finally {
      ui.startButton.disabled = false;
    }
  });

  // ---- Recent runs (kept in the browser; the page works without them) ----------------------------------------

  function loadRecent() {
    try {
      const stored = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
      return Array.isArray(stored)
        ? stored.filter(entry => isObject(entry) && RUN_ID.test(entry.id)).map(entry => ({ id: entry.id, text: asText(entry.text) }))
        : [];
    } catch (error) {
      console.warn('event=recent_runs_unavailable', error);
      return [];
    }
  }

  function remember(id, requirement) {
    try {
      const runs = [{ id, text: requirement.trim().slice(0, 80) }, ...loadRecent().filter(entry => entry.id !== id)];
      localStorage.setItem(RECENT_KEY, JSON.stringify(runs.slice(0, MAX_RECENT)));
    } catch (error) {
      console.warn('event=recent_runs_not_saved', error);
    }
  }

  function renderRecent() {
    const runs = loadRecent();
    ui.recent.hidden = runs.length === 0;
    ui.recentList.replaceChildren(...runs.map(entry => el('li', {},
      el('a', { href: `#run=${entry.id}` },
        el('span', { class: 'recent-text', text: entry.text }), el('span', { class: 'mono', text: entry.id.slice(0, 8) })))));
  }

  window.addEventListener('hashchange', route);
  route();
})();
