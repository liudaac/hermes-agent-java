import { createRoot } from "react-dom/client";
import "./theme.css";
import App from "./App";

const root = document.getElementById("root");
if (!root) throw new Error("Portal: #root not found");

createRoot(root).render(<App />);
