#!/usr/bin/env python3

# pip3 install docker
import docker
import sys


def get_latest_package_versions(package_names, linux_version="oraclelinux:8"):
    try:
        client = docker.from_env()

        print(f"Pulling Docker image {linux_version}...")
        client.images.pull(linux_version)

        print("Starting the container...")
        container = client.containers.run(
            image=linux_version,
            command="tail -f /dev/null",  # Keep the container running
            detach=True,
        )

        versions = {}
        try:
            print("Updating package database in the container...")
            # Update package database
            container.exec_run("yum makecache --enablerepo=ol8_codeready_builder", user="root")
            container.exec_run("yum install -y yum-utils", user="root")

            print("Checking latest versions of packages...")
            for p in package_names:
                print(f"Checking package: {p}... ", end="")
                result = container.exec_run(
                    f'repoquery --enablerepo=ol8_codeready_builder --latest-limit=1 --queryformat="%{{name}}-%{{version}}-%{{release}}" {p}', user="root")
                output = result.output.decode()
                pv = get_package_version(output, p)
                if pv is not None:
                    print(pv)
                    versions[p] = pv
                else:
                    print(f"Error: '{output}'")
                    versions[p] = None

            return versions

        finally:
            print("Stopping and removing container...")
            container.stop()
            container.remove()

    except Exception as e:
        print(f"An error occurred: {e}")
        sys.exit(1)


def get_package_version(output, p):
    lines = output.splitlines()
    v = None
    for line in lines:
        if line.startswith(p):
            v = line
            break
    return v


if __name__ == "__main__":
    package_list = [
                "alsa-lib-devel",
                "at-spi2-atk-devel",
                "autoconf",
                "automake",
                "bison",
                "bzip2-libs",
                "cairo-devel",
                "cups-devel",
                "file",
                "flex",
                "fontconfig-devel",
                "freetype-devel",
                "git",
                "git-core",
                "libtool",
                "libXcomposite",
                "libXdamage",
                "libXi-devel",
                "libxkbcommon",
                "libXrandr-devel",
                "libXrender-devel",
                "libXt-devel",
                "libXtst-devel",
                "make-devel",
                "mesa-libgbm",
                "ninja-build",
                "perl-IPC-Cmd",
                "rsync-3.1.3",
                "unzip-6.0",
                "wayland-devel",
                "wayland-protocols-devel",
                "pango-devel",
                "python36",
                "cmake",
                "vulkan-headers",
                "vulkan-loader",
                "vulkan-validation-layers"
            ]

    print("Fetching package versions...\n")
    package_versions = get_latest_package_versions(package_list)

    print("\nPackage Versions:")
    for p in package_list:
        version = package_versions[p]
        name = p.ljust(25)
        print(f"{name}: {version}")

    print("\nInstall command:\n")
    print("    yum -y install --enablerepo=ol8_codeready_builder \\")
    for p in package_list:
        version = package_versions[p]
        if version is not None:
            print(f"        {version} \\")

