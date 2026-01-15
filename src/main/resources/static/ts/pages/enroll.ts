import { getById, onReady } from "../shared.js";
import { createNewKeyPair } from "../util/keys-util.js";
import { createEnrollmentJwt, unpackEnrollmentToken } from "../util/token-util.js";
import { postEnrollComplete } from "../util/http-util.js";

onReady(() => {
  const qs = new URLSearchParams(location.search);

  // query parameter fields
  const tokenEl = getById<HTMLInputElement>("token");
  const contextEl = getById<HTMLInputElement>("context");
  const iamUrlEl = getById<HTMLInputElement>("iam-url");
  const outEl = getById<HTMLInputElement>("out");

  // actions
  const createJwkBtn = getById<HTMLInputElement>("createJwkBtn");
  const enrollBtn = getById<HTMLInputElement>("enrollBtn");
  const enrollBtnBackend = getById<HTMLInputElement>("enrollBtnBackend");

  tokenEl.value = qs.get("token") ?? "";
  contextEl.value = qs.get("context") ?? "";
  iamUrlEl.value = qs.get("url") ?? "";

  createJwkBtn.addEventListener("click", async () => {
    await createNewKeyPair();
  });

  enrollBtnBackend.addEventListener("click", async () => {
    const _token = tokenEl.value.trim();
    const _context = contextEl.value.trim();
    let _iamUrl: string | URL = iamUrlEl.value.trim();
    if (!_token) {
      outEl.textContent = "Please enter token.";
      return;
    }
    if (_iamUrl) {
      try {
        _iamUrl = new URL(_iamUrl);
      } catch (e) {
        outEl.textContent = "Not a valid url.";
        return;
      }
    }

    outEl.textContent = "Starting backend enrollment...";
    try {
      const formData = new FormData();
      formData.append("token", _token);
      if (_context) formData.append("context", _context);
      formData.append("_iamUrl", _iamUrl ? _iamUrl.toString() : "http://localhost:8080/realms/demo/push-mfa/enroll/complete");

      const response = await fetch("/enroll/complete", {
        method: "POST",
        body: formData,
      });

      if (!response.ok) {
        const error = await response.text();
        outEl.textContent = "Error: " + error;
        return;
      }
      const data = await response.text();
      outEl.textContent = data;
    } catch (e) {
      outEl.textContent = "Error: " + (e instanceof Error ? e.message : String(e));
    }
  });

  enrollBtn.addEventListener("click", async () => {
    const _token = tokenEl.value.trim();
    const _context = contextEl.value.trim();
    let _iamUrl: string | URL = iamUrlEl.value.trim();
    if (!_token) {
      outEl.textContent = "Please enter token.";
      return;
    }
    if (_iamUrl) {
      try {
        _iamUrl = new URL(_iamUrl);
      } catch (e) {
        outEl.textContent = "Not a valid url.";
        return;
      }
    }

    outEl.textContent = "Starting enrollment...";
    try {
      const enrollmentValues = unpackEnrollmentToken(_token);
      if (enrollmentValues === null) {
        outEl.textContent = "invalid enrollment token payload";
        return;
      }
      const enrollmentJwt = await createEnrollmentJwt(enrollmentValues, _context);
      const keycloakResponse = await postEnrollComplete(enrollmentJwt, _iamUrl as URL);

      if (!keycloakResponse.ok) {
        const keycloakError = await keycloakResponse.text();
        outEl.textContent = "KeycloakError: " + keycloakError;
        return;
      }
      const data = await keycloakResponse.text();
      outEl.textContent = JSON.stringify(data, null, 2);
    } catch (e) {
      outEl.textContent = "Error: " + (e instanceof Error ? e.message : String(e));
    }
  });
});
