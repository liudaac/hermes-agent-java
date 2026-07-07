import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "./theme.css";
import { NocRouter } from "./router";
import { I18nProvider } from "./i18n";
import { exposePluginSDK } from "./plugins";
import { ThemeProvider } from "./themes";
import { ToastProvider } from "./hooks/useToast";

exposePluginSDK();

createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <I18nProvider>
      <ThemeProvider>
        <ToastProvider>
          <NocRouter />
        </ToastProvider>
      </ThemeProvider>
    </I18nProvider>
  </BrowserRouter>,
);
