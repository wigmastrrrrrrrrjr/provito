const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...CORS },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

    if (url.pathname === "/message/create" && request.method === "POST") {
      let body = {};
      try {
        const ct = request.headers.get("content-type") || "";
        body = ct.includes("application/json") ? await request.json() : { text: await request.text() };
      } catch {
        return json({ error: "invalid body" }, 400);
      }
      const text = String(body.text ?? body.message ?? "").trim();
      if (!text) return json({ error: "text is required" }, 400);
      const author = String(body.author ?? "anon").trim().slice(0, 40) || "anon";
      const id = `${String(Date.now()).padStart(13, "0")}-${Math.random().toString(36).slice(2, 8)}`;
      const at = new Date().toISOString();
      const message = { id, author, text: text.slice(0, 8000), at };
      await env.DB.prepare("INSERT INTO messages (id, author, text, at) VALUES (?, ?, ?, ?)")
        .bind(id, author, message.text, at)
        .run();
      return json(message, 201);
    }

    if (url.pathname === "/messages" && request.method === "GET") {
      const since = url.searchParams.get("since") || "";
      const { results } = await env.DB.prepare(
        "SELECT id, author, text, at FROM messages WHERE id > ? ORDER BY id ASC LIMIT 1000"
      ).bind(since).all();
      if (url.searchParams.get("format") === "text") {
        const text = results.map((m) => `[${m.at}] [${m.author}] ${m.text}`).join("\n") + "\n";
        return new Response(text, {
          headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store", ...CORS },
        });
      }
      return json({ count: results.length, messages: results });
    }

    return new Response(
      "Provito agent chat API\n\n" +
        'POST /message/create   {"author":"backend","text":"hello"}\n' +
        "GET  /messages         all messages (?since=<id> for newer only)\n",
      { headers: { "Content-Type": "text/plain; charset=utf-8", ...CORS } }
    );
  },
};
