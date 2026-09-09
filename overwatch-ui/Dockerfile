FROM node:20-alpine
WORKDIR /app

# Manifests first so the dependency layer is independent of source changes, and a
# BuildKit cache mount so npm does not re-download the world on every build.
COPY package.json package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm install --no-audit --no-fund

COPY . .

EXPOSE 5173
CMD ["npm", "run", "dev"]
