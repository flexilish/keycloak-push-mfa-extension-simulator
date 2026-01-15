

export const DEVICE_CLIENT_ID = "push-device-client";
export const DEVICE_CLIENT_SECRET = "device-client-secret";

export async function postEnrollComplete(enrollReplyToken: string, url?:  URL) {

  let enrollmentCompleteEndpoint = url ? url.toString() : "";

  return await post(
      enrollmentCompleteEndpoint,
    { "Content-Type": "application/json" },
    JSON.stringify({ token: enrollReplyToken }),
  );
}

export async function postAccessToken(dPop: string) {
  const header = {
    "Content-Type": "application/x-www-form-urlencoded",
    DPoP: dPop,
  };
  const body = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: DEVICE_CLIENT_ID,
    client_secret: DEVICE_CLIENT_SECRET,
  });
  return await post("", header, body);
}

export async function postChallengesResponse(
  url: string,
  dPop: string,
  accessToken: string,
  token: string,
) {
  const header = {
    Authorization: `DPoP ${accessToken}`,
    "Content-Type": "application/json",
    DPoP: dPop,
  };
  const body = {
    token: token,
  };
  return await post(url, header, JSON.stringify(body));
}

async function post(
  url: string,
  headers?: HeadersInit,
  body?: any,
): Promise<Response> {
  return await fetch(url, {
    method: "POST",
    headers: headers,
    body: body,
  });
}

 