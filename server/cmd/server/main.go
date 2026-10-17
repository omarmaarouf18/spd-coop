// Pixel Dungeon
// Copyright (C) 2012-2015 Oleg Dolya
//
// Shattered Pixel Dungeon
// Copyright (C) 2014-2026 Evan Debenham
//
// Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>

// Command server is the Logic Link PD co-op server (architecture §4).
//
//	server               run the server (config from env: PORT, DB_PATH,
//	                     LOG_LEVEL, ALLOWED_VERSIONS)
//	server -check-env    validate the config and exit 0/1 without starting
//	                     (the deploy pre-flight)
//	server -healthcheck  GET http://127.0.0.1:$PORT/healthz and exit 0/1
//	                     (the compose healthcheck: distroless has no shell)
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/config"
	"github.com/omarmaarouf18/spd-coop/server/internal/httpapi"
	"github.com/omarmaarouf18/spd-coop/server/internal/lobby"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// version is set at build time: -ldflags "-X main.version=<sha>".
var version = "dev"

const shutdownTimeout = 10 * time.Second

func main() {
	checkEnv := flag.Bool("check-env", false, "validate the configuration and exit")
	healthcheck := flag.Bool("healthcheck", false, "GET /healthz on the local server and exit 0 if healthy")
	flag.Parse()

	cfg, err := config.Load(os.Getenv)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid configuration:\n%v\n", err)
		os.Exit(1)
	}

	switch {
	case *checkEnv:
		fmt.Printf("configuration OK: port=%d db_path=%s log_level=%s allowed_versions=%v version=%s\n",
			cfg.Port, cfg.DBPath, cfg.LogLevel, cfg.AllowedVersions, version)
	case *healthcheck:
		if err := runHealthcheck(cfg.Port); err != nil {
			fmt.Fprintf(os.Stderr, "healthcheck failed: %v\n", err)
			os.Exit(1)
		}
	default:
		if err := run(cfg); err != nil {
			slog.Error("server failed", "err", err)
			os.Exit(1)
		}
	}
}

func runHealthcheck(port int) error {
	client := &http.Client{Timeout: 3 * time.Second}
	res, err := client.Get("http://" + net.JoinHostPort("127.0.0.1", strconv.Itoa(port)) + "/healthz")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, res.Body)
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("GET /healthz returned %s", res.Status)
	}
	return nil
}

func run(cfg config.Config) error {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	st, err := store.Open(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer st.Close()

	hub := lobby.New(lobby.Options{
		Store:           st,
		Logger:          logger,
		AllowedVersions: cfg.AllowedVersions,
		Shutdown:        ctx,
	})
	srv := &http.Server{
		Addr:              ":" + strconv.Itoa(cfg.Port),
		Handler:           httpapi.New(httpapi.Options{Version: version, WS: hub}),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	logger.Info("server started",
		"version", version,
		"port", cfg.Port,
		"db_path", cfg.DBPath,
		"schema_version", store.SchemaVersion,
		"epoch", hub.Epoch(),
		"allowed_versions", fmt.Sprint(cfg.AllowedVersions))

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}

	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("graceful shutdown: %w", err)
	}
	if err := <-errc; err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("server stopped")
	return nil
}
