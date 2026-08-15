import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { I18nProvider } from "@hermes/ui";
import { AdminApp } from "./App";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter basename="/admin">
      <I18nProvider>
        <AdminApp />
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
);
