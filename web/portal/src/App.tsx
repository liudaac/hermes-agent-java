import { BrowserRouter } from "react-router-dom";
import { I18nProvider } from "@/i18n";
import { PortalRouter } from "@/router";

export default function App() {
  return (
    <I18nProvider initial="zh-CN">
      <BrowserRouter>
        <PortalRouter />
      </BrowserRouter>
    </I18nProvider>
  );
}
