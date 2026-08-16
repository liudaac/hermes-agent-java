import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "./theme.css";
import { OpsRouter } from "./router";
import { I18nProvider } from "./i18n";
import { ToastProvider } from "./hooks/useToast";
import { exposePluginSDK } from "./plugins";

exposePluginSDK();

createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <I18nProvider>
      <ToastProvider>
        <OpsRouter />
      </ToastProvider>
    </I18nProvider>
  </BrowserRouter>,
);
