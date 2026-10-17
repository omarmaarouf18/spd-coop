# server/Dockerfile — Logic Link PD co-op server image (SRV-00, D-014).
#
# Multi-stage: a static CGO-free build, then distroless/static (no shell, no
# package manager), running as nonroot. The golang tag must match GO_VERSION
# in tools/versions.env (scripts/check-versions.sh enforces it).
# The healthcheck lives in compose (`/server -healthcheck`), since distroless
# has no shell or curl.

FROM golang:1.27.2-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/server ./cmd/server \
	&& mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/server /server
# /data is the state volume (DB_PATH=/data/coop.db). Owned by nonroot in the
# image, so a fresh named volume mounted there inherits that ownership.
COPY --from=build --chown=65532:65532 /out/data /data
USER nonroot
EXPOSE 8080
ENTRYPOINT ["/server"]
