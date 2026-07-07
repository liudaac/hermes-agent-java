import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "./theme.css";
import { OpsRouter } from "./router";
import { I18nProvider } from "./i18n";
import { exposePluginSDK } from "./plugins";
import { ThemeProvider } from "./themes";
import { ToastProvider } from "./hooks/useToast";

// Expose the plugin SDK before rendering so plugins loaded via <script>
// can access React, components, etc. immediately.
exposePluginSDK();

createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <I18nProvider>
      <ThemeProvider>
        <ToastProvider>
          <OpsRouter />
        </ToastProvider>
      </ThemeProvider>
    </I18nProvider>
  </BrowserRouter>,
);
