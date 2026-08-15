import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { I18nProvider } from "@hermes/ui";
import { DevPortalApp } from "./App";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter basename="/devportal">
      <I18nProvider>
        <DevPortalApp />
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
);
