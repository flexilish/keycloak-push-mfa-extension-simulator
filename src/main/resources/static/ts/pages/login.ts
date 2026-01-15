import { getById, onReady, setMessage } from "../shared.js";

onReady(() => {
  // Login-Seite spezifische Logik
  const loginForm = getById<HTMLFormElement>("login-form");
  const messageEl = getById<HTMLElement>("message");

  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    
    setMessage(messageEl, "Logging in...", "info");
    
    try {
      // Login-Logik hier
      console.log("Login attempt");
    } catch (e) {
      setMessage(
        messageEl,
        "Error: " + (e instanceof Error ? e.message : String(e)),
        "error"
      );
    }
  });
});
