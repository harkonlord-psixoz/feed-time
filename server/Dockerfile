FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY migrations ./migrations
COPY src ./src

ENV NODE_ENV=production
ENV PORT=3080
EXPOSE 3080

CMD ["sh", "-c", "node src/migrate.js && node src/index.js"]
