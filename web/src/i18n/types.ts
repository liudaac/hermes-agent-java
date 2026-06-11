export type Locale = "en" | "zh";

export interface Translations {
  // ── Common ──
  common: {
    save: string;
    saving: string;
    cancel: string;
    close: string;
    delete: string;
    refresh: string;
    retry: string;
    search: string;
    loading: string;
    create: string;
    creating: string;
    set: string;
    replace: string;
    clear: string;
    live: string;
    off: string;
    enabled: string;
    disabled: string;
    active: string;
    inactive: string;
    unknown: string;
    untitled: string;
    none: string;
    form: string;
    noResults: string;
    of: string;
    page: string;
    msgs: string;
    tools: string;
    match: string;
    other: string;
    configured: string;
    removed: string;
    failedToToggle: string;
    failedToRemove: string;
    failedToReveal: string;
    collapse: string;
    expand: string;
    general: string;
    messaging: string;
  };

  // ── App shell ──
  app: {
    brand: string;
    brandShort: string;
    webUi: string;
    footer: {
      name: string;
      org: string;
    };
    nav: {
      status: string;
      sessions: string;
      analytics: string;
      logs: string;
      cron: string;
      skills: string;
      tools: string;
      tenants: string;
      org: string;
      orgOverview: string;
      orgManage: string;
      orgControl: string;
      config: string;
      keys: string;
      playground: string;
      compare: string;
    };
  };

  // ── Status page ──
  status: {
    actionFailed: string;
    actionFinished: string;
    actions: string;
    agent: string;
    connected: string;
    connectedPlatforms: string;
    disconnected: string;
    error: string;
    failed: string;
    gateway: string;
    gatewayFailedToStart: string;
    lastUpdate: string;
    noneRunning: string;
    notRunning: string;
    pid: string;
    platformDisconnected: string;
    platformError: string;
    activeSessions: string;
    recentSessions: string;
    restartGateway: string;
    restartingGateway: string;
    running: string;
    runningRemote: string;
    startFailed: string;
    starting: string;
    startedInBackground: string;
    stopped: string;
    updateHermes: string;
    updatingHermes: string;
    waitingForOutput: string;
  };

  // ── Sessions page ──
  sessions: {
    title: string;
    searchPlaceholder: string;
    noSessions: string;
    noMatch: string;
    startConversation: string;
    noMessages: string;
    untitledSession: string;
    deleteSession: string;
    previousPage: string;
    nextPage: string;
    roles: {
      user: string;
      assistant: string;
      system: string;
      tool: string;
    };
  };

  // ── Analytics page ──
  analytics: {
    period: string;
    totalTokens: string;
    totalSessions: string;
    apiCalls: string;
    dailyTokenUsage: string;
    dailyBreakdown: string;
    perModelBreakdown: string;
    topSkills: string;
    skill: string;
    loads: string;
    edits: string;
    lastUsed: string;
    input: string;
    output: string;
    total: string;
    noUsageData: string;
    startSession: string;
    date: string;
    model: string;
    tokens: string;
    perDayAvg: string;
    acrossModels: string;
    inOut: string;
  };

  // ── Logs page ──
  logs: {
    title: string;
    autoRefresh: string;
    file: string;
    level: string;
    component: string;
    lines: string;
    noFiles: string;
    noLogLines: string;
  };

  // ── Cron page ──
  cron: {
    newJob: string;
    nameOptional: string;
    namePlaceholder: string;
    prompt: string;
    promptPlaceholder: string;
    schedule: string;
    schedulePlaceholder: string;
    deliverTo: string;
    scheduledJobs: string;
    noJobs: string;
    last: string;
    next: string;
    pause: string;
    resume: string;
    triggerNow: string;
    delivery: {
      local: string;
      telegram: string;
      discord: string;
      slack: string;
      email: string;
    };
  };

  // ── Skills page ──
  skills: {
    title: string;
    searchPlaceholder: string;
    enabledOf: string;
    all: string;
    noSkills: string;
    noSkillsMatch: string;
    skillCount: string;
    resultCount: string;
    noDescription: string;
    toolsets: string;
    toolsetLabel: string;
    noToolsetsMatch: string;
    setupNeeded: string;
    disabledForCli: string;
    more: string;
  };

  // ── Config page ──
  config: {
    configPath: string;
    exportConfig: string;
    importConfig: string;
    resetDefaults: string;
    rawYaml: string;
    searchResults: string;
    fields: string;
    noFieldsMatch: string;
    configSaved: string;
    yamlConfigSaved: string;
    failedToSave: string;
    failedToSaveYaml: string;
    failedToLoadRaw: string;
    configImported: string;
    invalidJson: string;
    categories: {
      general: string;
      agent: string;
      terminal: string;
      display: string;
      delegation: string;
      memory: string;
      compression: string;
      security: string;
      browser: string;
      voice: string;
      tts: string;
      stt: string;
      logging: string;
      discord: string;
      auxiliary: string;
    };
  };

  // ── Env / Keys page ──
  env: {
    description: string;
    changesNote: string;
    hideAdvanced: string;
    showAdvanced: string;
    llmProviders: string;
    providersConfigured: string;
    getKey: string;
    notConfigured: string;
    notSet: string;
    keysCount: string;
    enterValue: string;
    replaceCurrentValue: string;
    showValue: string;
    hideValue: string;
  };

  // ── OAuth ──
  oauth: {
    title: string;
    providerLogins: string;
    description: string;
    connected: string;
    expired: string;
    notConnected: string;
    runInTerminal: string;
    noProviders: string;
    login: string;
    disconnect: string;
    managedExternally: string;
    copied: string;
    cli: string;
    copyCliCommand: string;
    connect: string;
    sessionExpires: string;
    initiatingLogin: string;
    exchangingCode: string;
    connectedClosing: string;
    loginFailed: string;
    sessionExpired: string;
    reOpenAuth: string;
    reOpenVerification: string;
    submitCode: string;
    pasteCode: string;
    waitingAuth: string;
    enterCodePrompt: string;
    pkceStep1: string;
    pkceStep2: string;
    pkceStep3: string;
    flowLabels: {
      pkce: string;
      device_code: string;
      external: string;
    };
    expiresIn: string;
  };

  // ── Language switcher ──
  language: {
    switchTo: string;
  };

  // ── Theme switcher ──
  theme: {
    title: string;
    switchTheme: string;
  };

  // ── Playground page ──
  playground: {
    title: string;
    tenantId: string;
    tenantIdPlaceholder: string;
    sessionId: string;
    sessionIdPlaceholder: string;
    modelParams: string;
    custom: string;
    temperature: string;
    temperaturePlaceholder: string;
    maxTokens: string;
    maxTokensPlaceholder: string;
    topP: string;
    topPPlaceholder: string;
    enableReasoning: string;
    resetDefaults: string;
    systemPrompt: string;
    systemPromptPlaceholder: string;
    systemPromptHint: string;
    reset: string;
    startConversation: string;
    edit: string;
    retry: string;
    debugInfo: string;
    tokenUsage: string;
    prompt: string;
    completion: string;
    cached: string;
    reasoning: string;
    total: string;
    toolCalls: string;
    listening: string;
    stopRecording: string;
    voiceInput: string;
    typeMessage: string;
    send: string;
    cancel: string;
    saveAndSend: string;
    speechError: string;
  };

  // ── Compare page ──
  compare: {
    title: string;
    vs: string;
    participants: string;
    addParticipant: string;
    removeParticipant: string;
    clearBoth: string;
    tenantIdPlaceholder: string;
    waiting: string;
    autoChatRunning: string;
    autoChatMode: string;
    collapse: string;
    expand: string;
    initialTopic: string;
    initialTopicPlaceholder: string;
    rounds: string;
    roundsHint: string;
    stop: string;
    startAutoChat: string;
    askBoth: string;
    send: string;
    conclusion: string;
    generateConclusion: string;
    conclusionLoading: string;
    history: string;
    refreshHistory: string;
    openRun: string;
    runCreated: string;
    runUpdated: string;
    runEvents: string;
    runError: string;
    noHistory: string;
    runningRunRestoreNotice: string;
    autoChatStopped: string;
  };

  // ── Tenants page ──
  tenants: {
    title: string;
    countSummary: string;
    refresh: string;
    createTenant: string;
    create: string;
    creating: string;
    tenantIdPlaceholder: string;
    tenantList: string;
    searchPlaceholder: string;
    loading: string;
    noTenantsFound: string;
    agentsSessions: string;
    selectHint: string;
    apiHint: string;
    protected: string;
    suspend: string;
    resume: string;
    delete: string;
    cannotSuspendDefault: string;
    cannotDeleteDefault: string;
    loadingDetails: string;
    state: string;
    activeAgents: string;
    activeSessions: string;
    tenantSkills: string;
    created: string;
    lastActivity: string;
    usage: string;
    quota: string;
    security: string;
    agentConfig: string;
    dailyRequests: string;
    dailyTokens: string;
    storage: string;
    memory: string;
    noUsageData: string;
    maxDailyRequests: string;
    maxDailyTokens: string;
    concurrentAgents: string;
    concurrentSessions: string;
    maxStorageBytes: string;
    maxMemoryBytes: string;
    saveQuota: string;
    savingQuota: string;
    codeExecution: string;
    sandboxRequired: string;
    networkAccess: string;
    fileRead: string;
    fileWrite: string;
    allowedLanguages: string;
    allowedHosts: string;
    allowedTools: string;
    deniedTools: string;
    deniedPaths: string;
    allowedToolsHint: string;
    deniedPathsHint: string;
    saveSecurity: string;
    savingSecurity: string;
    systemPrompt: string;
    systemPromptPlaceholder: string;
    model: string;
    provider: string;
    temperature: string;
    maxTokens: string;
    saveConfig: string;
    savingConfig: string;
    noSkillsInstalled: string;
    noDescription: string;
    readOnly: string;
    recentAudit: string;
    noAuditEvents: string;
    deleteConfirm: string;
    codeExecutionConfirm: string;
    networkAccessConfirm: string;
    toastFailedLoad: string;
    toastCreated: string;
    toastCreateFailed: string;
    toastDeleted: string;
    toastSuspended: string;
    toastResumed: string;
    toastActionFailed: string;
    toastQuotaSaved: string;
    toastQuotaSaveFailed: string;
    toastSecuritySaved: string;
    toastSecuritySaveFailed: string;
    toastConfigSaved: string;
    toastConfigSaveFailed: string;
  };

  // ── Org page ──
  org: {
    title: string;
    subtitle: string;
    refresh: string;
    wireHint: string;
    noModules: string;
    notWired: string;
    wireEmpty: string;
    tabs: {
      overview: string;
      identity: string;
      handoff: string;
      auth: string;
      knowledge: string;
      workflow: string;
      market: string;
      observe: string;
      distributed: string;
      evolution: string;
      compliance: string;
    };
    totalIdentities: string;
    active: string;
    deactivated: string;
    oidcBound: string;
    expiringCredentials: string;
    pending: string;
    acknowledged: string;
    escalated: string;
    resolved: string;
    pendingHandoffs: string;
    subjects: string;
    customRoles: string;
    abacPolicies: string;
    overrides: string;
    totalEntries: string;
    uniqueTags: string;
    uniqueTopics: string;
    staleEntries: string;
    activeWorkflows: string;
    waitingHuman: string;
    completed: string;
    failed: string;
    workflowTemplates: string;
    compensated: string;
    today: string;
    thisMonth: string;
    forecast: string;
    totalTemplates: string;
    totalInstalls: string;
    templateTemplates: string;
    activeTraces: string;
    totalTraces: string;
    agentsTracked: string;
    recentAnomalies: string;
    totalAgents: string;
    totalNodes: string;
    capabilities: string;
    registeredNodes: string;
    load: string;
    ok: string;
    down: string;
    totalFailures: string;
    resolutionRate: string;
    pendingSuggestions: string;
    residencyRules: string;
    agentsWithRegions: string;
  };


  orgManage: {
    title: string;
    subtitle: string;
    failedToLoad: string;
    saveFailed: string;
    deleteFailed: string;
    deleteConfirm: string;
    relationship: { tenants: string; tenantsDesc: string; orgManagement: string; orgManagementDesc: string; orgControl: string; orgControlDesc: string; };
    form: { title: string; saveRole: string; csvPlaceholder: string; tagPlaceholder: string; addTag: string; note: string; };
    fields: { tenant: string; agentId: string; roleName: string; description: string; level: string; skills: string; responsibilities: string; reportsTo: string; allowedTools: string; teams: string; };
    roles: { title: string; empty: string; edit: string; };
    teams: { title: string; formTitle: string; empty: string; saveTeam: string; saveFailed: string; deleteFailed: string; deleteConfirm: string; teamId: string; name: string; mission: string; members: string; lead: string; showDetails: string; hideDetails: string; missingRole: string; };
    filters: { title: string; all: string; team: string; search: string; searchPlaceholder: string; missingOnly: string; };
    audit: {
      title: string;
      empty: string;
      events: {
        roleCreated: string;
        roleUpdated: string;
        roleDeleted: string;
        teamCreated: string;
        teamUpdated: string;
        teamDeleted: string;
      };
    };
  };

  // ── Org Control Center ──
  orgControl: {
    title: string;
    subtitle: string;
    failedToLoad: string;
    controlActionFailed: string;
    reasonPrompt: string;
    chooseTargetAgentError: string;
    endpointUrl: string;
    endpointUrlToProbe: string;
    configJson: string;
    metrics: { tenants: string; teams: string; members: string; intentRuns: string; traces: string; failures: string; anomalies: string; };
    sections: { teams: string; intentRuns: string; recentTraces: string; anomalies: string; controlTimeline: string; browserControls: string; browserApprovals: string; browserTimeline: string; evolution: string; tenantRows: string; delegatedTasks: string; };
    empty: { teams: string; intentRuns: string; traces: string; anomalies: string; audit: string; browserStatus: string; browserApprovals: string; browserActions: string; evolution: string; delegatedTasks: string; };
    buttons: { replayFailed: string; reroute: string; apply: string; monitor: string; healthCheck: string; capabilities: string; contractTest: string; probe: string; resetMock: string; clearConfig: string; exportConfig: string; applyRecommendation: string; approveOnce: string; reject: string; disable: string; deprioritize: string; restore: string; submitResult: string; verify: string; };
    labels: { noMission: string; members: string; agentRoutingControls: string; expires: string; parent: string; succeededFailedSubtasks: string; scoringExplanation: string; noRole: string; score: string; failedSubtasks: string; chooseTargetAgent: string; suggested: string; provider: string; persisted: string; ready: string; unhealthy: string; protocol: string; actions: string; features: string; probeScore: string; compatible: string; partial: string; recommended: string; candidates: string; contract: string; failed: string; pass: string; fail: string; ok: string; url: string; target: string; blocked: string; session: string; trace: string; expiresLower: string; teams: string; traces: string; resolved: string; rate: string; };
    delegated: { summaryPrompt: string; changedFilesPrompt: string; testsPrompt: string; risksPrompt: string; allowedPrefixesPrompt: string; requireTestsConfirm: string; requireAllTestsPassedConfirm: string; files: string; tests: string; statusFilter: string; verificationHistory: string; latest: string; showHistory: string; hideHistory: string; accepted: string; reason: string; policy: string; testsRequired: string; testsOptional: string; allTestsPassed: string; failedTestsAllowed: string; paths: string; };
    reasons: { replayFailed: string; rerouteFailed: string; overrideAgent: string; health: string; capabilities: string; contract: string; probe: string; applyProbe: string; resetMock: string; clearConfig: string; setProvider: string; approvalDecision: string; };
  };
}
