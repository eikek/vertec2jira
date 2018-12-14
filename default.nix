{ pkgs ? import <nixpkgs> {} }:

with pkgs.lib;

let
  filterLine = line: if ((builtins.isString line) && (builtins.match "val version = .*" line) == null) then false else true;
  versionLine = builtins.head (builtins.filter filterLine (builtins.filter builtins.isString (builtins.split "\n" (builtins.readFile ./vertec2jira.sc))));
  version = builtins.head (builtins.match ''val version *= *"(.*)"'' versionLine);
in
pkgs.stdenv.mkDerivation {
  name = "vertec2jira-${version}";

  src = ./.;

  unpackPhase = "true";
  buildPhase = "true";

  installPhase = ''
    mkdir -p $out/{bin,vertec2jira}
    cd $src
    cp vertec2jira.sc $out/vertec2jira
    cat > $out/bin/vertec2jira <<-EOF
    #!/usr/bin/env bash
    export PATH=\$PATH:${pkgs.ammonite}/bin
    $out/vertec2jira/vertec2jira.sc "\$@"
    EOF
    chmod 755 $out/bin/vertec2jira
  '';
}
