# vertec -> jira

Allow to process exports from Vertec (XLSX files) and look for
mentioned Jira tickets to add work logs items to them.

## Installation

It is recommended to use the [nix](https://nixos.org/nix) package
manager, which is available for OSX and most Linuxes. Then use it to
install this script:

``` shell
nix-env -if https://github.com/eikek/vertec2jira/archive/master.tar.gz
```

Or you can clone this repo, checkout the desired version and run:

``` shell
nix-env -if default.nix
```

Otherwise:
1. Install [ammonite](https://ammonite.io)
2. create a directory somewhere (maybe `/opt/vertec2jira`?)
3. Copy the script file into that directory
4. Symlink the script somewhere that is in your `$PATH`


## Usage

1. Go into Vertec, export a timesheet as Excel file.
2. Run the script with that file using your jira credentials:

``` shell
vertec2jira --xls /path/to/export.xlsx --jira-user xyz --jira-password bla --jira-url https://jira.mysite.com
```

One of the `--jira-pass*` options is required, as well as the
`--jira-user` and `--jira-url` option; and, of course, the `--xls`
option is required.

If you also add `--dry-run`, then nothing is mutated in Jira, but it
is printed what would have been done.

Here is an options overview:

```
Vertec2Jira 2018-12-12
Usage: vertec2jira [options]
  --usage  <bool>
        Print usage and exit
  --help | -h  <bool>
        Print help message and exit
  --xls  <file>
        The Excel file to process.
  --sheet  <sheet-number>
        The sheet number in the excel file. Default 0.
  --dry-run  <bool>
        Do not really modify a jira ticket. Default: false.
  --issue-filter  <regex>
        Filter jira issues by a regular expression. Default '.*'
  --vertec-comment-index  <index>
        The column in the excel export that denotes the user comment. Default 5.
  --vertec-effort-index  <index>
        The column in the excel export that denotes the effort (in hours). Default 6.
  --vertec-date-index  <index>
        The column in the excel export that denotes the date. Default 3.
  --jira-rest-version  <version>
        The version of JIRA's rest api to use. Default: 2
  --jira-url  <url>
        The base url to the JIRA installation.
  --jira-user  <string>
        The user to log into JIRA.
  --jira-password  <string>
        The password used to log into JIRA.
  --jira-pass-cmd  <string>
        A system command that returns the JIRA password as first line.
  --jira-pass-entry  <string>
        If you use the `pass` password manager, specify an entry to use.
```

You should maintain your vertec like this:

- put jira ticket keys into your message
- only use one line per day per ticket (multiple entries for the same
  day and the same ticket are recognized as duplicates and just one is
  presvered).

## How it works

It is very simple. The excel export is read using the [apache
poi](https://poi.apache.org) library. The comments are searched for
jira issue names and then they are looked up at jira. If there is such
a ticket, its worklog is checked if the item already exists (that is,
there is a worklog entry for the same user and day!). If there is
none, the corresponding worklog is added.

## Known bugs

- When there is a vertec entry referencing multiple Jira tickets, the
  script asks the user what to do. Unfortunately, what you type is not
  echoed back to you. So you must type blindly; or just use one vertec
  entry per jira ticket :-)

## Testing

There are some basic tests that are executed via

```
./runtests.sc
```

The jira tests only work with a running jira instance, that should
have a project `TEST` and a ticket `TEST-1`. Otherwise, specify
options to point to your instance and ticket:

```
JiraOpts
Usage: jira-opts [options]
  --usage  <bool>
        Print usage and exit
  --help | -h  <bool>
        Print help message and exit
  --url  <string>
  --user  <string>
  --password  <string>
  --version  <string>
  --ticket  <string>
```

The options specify the Jira instance and login information. The
`ticket` option defaults to `TEST-1`, it should be an existing ticket
for testing (worklog items are added and removed).

The `shell.nix` file contains an expression to install jira via
[nixpkgs](https://nixos.org/nixpkgs) inside a NixOS container. But due
to licensing this cannot be done automatically. First, you must
download the oracle jdk8 manually as jira didn't run with openjdk
(instructions are printed by nix). Then you must create an account
with atlassian to get an evaluation license. If that is not too scary:

```
nix-shell --run create-jira-container
```

creates the container and installs jira. Jira is then available at
`10.250.0.2:9090` (see in the `shell.nix` file). After that the normal
`nixos-container` commands can be used. Deleting the container must be
done via

```
nix-shell --run delete-jira-container
```
