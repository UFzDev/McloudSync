FROM node:22-alpine

WORKDIR /app
COPY package.json /app/package.json
COPY server.mjs /app/server.mjs

ENV PORT=8787
ENV DATA_DIR=/data
ENV LOCK_TTL_MS=90000

VOLUME ["/data"]
EXPOSE 8787

CMD ["node", "server.mjs"]
