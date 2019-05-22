#!/bin/bash

set -xe

dir="${0%/*}"
cd $dir

tmpdir=dc2f-site-gh-pages
branchname=gh-pages
repo=git@github.com:hpoul/finalyzer-dc2f-site.git
basedir=..
outdir=deps/finalyzer-dc2f-site/public

if test -d $tmpdir ; then
  pushd $tmpdir
  actualbranch=`git rev-parse --abbrev-ref HEAD`
  if test "$actualbranch" != "$branchname" ; then
    echo "Invalid branch $actualbranch"
    exit 1
  fi
  git reset --hard
  git clean -d -x -f
  git pull
  popd
else
  git clone -b $branchname $repo $tmpdir
fi

diff -r $tmpdir $basedir/$outdir




