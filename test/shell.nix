# Create a container that runs jira. Once created, you can use
# standard nixos-container command.
#
# Note that jira must be setup first and it requires a trial /
# evaluation license key that you can get after signing up with
# Atlassian :-/
#
# Usage:
# $ nix-shell --run create-jira-container
#
{ pkgs ? (import <nixpkgs> {}) }:
let
  extra-container = pkgs.callPackage (builtins.fetchGit {
    url = "https://github.com/erikarvstedt/extra-container.git";
    # Recommended: Specify a git revision hash
    # rev = "...";
  }) {};
  jiracnt = pkgs.writeText "jira-cnt.nix" ''{
    containers.jira = {
      privateNetwork = true;
      hostAddress = "10.250.0.1";
      localAddress = "10.250.0.2";

      config = { pkgs, ... }: {
        networking = {
          firewall = {
            allowedTCPPorts = [ 9090 ];
          };
        };
        nixpkgs.config.allowUnfree = true;
        environment.systemPackages = [];

        services.jira = {
          enable = true;
  # Jira doesn't work with openjdk?!
  #        jrePackage = pkgs.jdk8;
          listenAddress = "0.0.0.0";
          listenPort = 9090;
        };
      };
    };
  }'';
  create = pkgs.writeScript "create-jira-container" ''
  #!/usr/bin/env bash
  sudo ${extra-container}/bin/extra-container create --start ${jiracnt}
  '';
  delete = pkgs.writeScript "delete-jira-container" ''
  #!/usr/bin/env bash
  sudo ${extra-container}/bin/extra-container destroy jira
  '';
  cmd = pkgs.stdenv.mkDerivation rec {
    name = "create-jira-container";
    buildInputs = [ ];
    unpackPhase = "true";
    buildPhase = "true";
    installPhase = ''
     mkdir -p $out/bin
     ln -snf ${create} $out/bin/${name}
    '';
  };
  cmddel = pkgs.stdenv.mkDerivation rec {
    name = "delete-jira-container";
    unpackPhase = "true";
    buildPhase = "true";
    installPhase = ''
      mkdir -p $out/bin
      ln -snf ${delete} $out/bin/${name}
    '';
  };
in pkgs.stdenv.mkDerivation {
  name = "tests";
  buildInputs = [ cmd cmddel ];
}
