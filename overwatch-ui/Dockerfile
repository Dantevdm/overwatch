FROM node:20-alpine
WORKDIR /app

# Manifests first so `npm ci` caches independently of source changes.
COPY package.json package-lock.json* ./
RUN npm install

COPY . .

EXPOSE 5173
CMD ["npm", "run", "dev"]
