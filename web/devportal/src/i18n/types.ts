export type Locale = "en" | "zh";

export interface Translations {
  common: {
    save: string;
    cancel: string;
    set: string;
    clear: string;
    replace: string;
    refresh: string;
    remove: string;
    failedToRemove: string;
    failedToReveal: string;
    other: string;
    of: string;
    configured: string;
    retry: string;
    close: string;
    messaging: string;
  };

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

  api: {
    title: string;
    description: string;
    method: string;
    endpoint: string;
    tryIt: string;
    request: string;
    response: string;
    send: string;
    parameters: string;
    pathParams: string;
    queryParams: string;
    body: string;
    headers: string;
    noEndpoint: string;
    filter: string;
    allMethods: string;
    copyUrl: string;
    copied: string;
  };

  webhooks: {
    title: string;
    description: string;
    url: string;
    events: string;
    secret: string;
    register: string;
    registered: string;
    noWebhooks: string;
    eventsPlaceholder: string;
    secretPlaceholder: string;
    urlPlaceholder: string;
    delete: string;
    test: string;
    testSent: string;
  };

  integration: {
    title: string;
    description: string;
    quickStart: string;
    sdkInstall: string;
    sdkUsage: string;
    authentication: string;
    examples: string;
    webhookSetup: string;
    oauthSetup: string;
    apiKeys: string;
  };

  language: {
    switchTo: string;
  };
}
