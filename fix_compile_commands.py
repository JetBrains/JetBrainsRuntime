import json
import os
import sys

def add_include_paths(compile_commands, paths):
    valid_paths = [path for path in paths if os.path.isdir(path)]
    if not valid_paths:
        print("No valid include paths found.")
        return

    try:
        with open(compile_commands, "r", encoding="utf-8") as file:
            commands = json.load(file)

        for entry in commands:
            command = entry["command"]

            for path in valid_paths:
                include_flag = f"-I{path}"
                if include_flag not in command:
                    command += f" {include_flag}"

            entry["command"] = command

        with open(compile_commands, "w", encoding="utf-8") as file:
            json.dump(commands, file, indent=4)

        print(f"Successfully added valid include paths: {valid_paths}")

    except Exception as e:
        print(f"Error while processing the file: {e}")

def remove_commands(compile_commands, commands_to_remove):
    try:
        with open(compile_commands, "r", encoding="utf-8") as file:
            commands = json.load(file)

        filtered_commands = [
            entry for entry in commands
            if not any(remove_substring in entry['command'] for remove_substring in commands_to_remove)
        ]

        with open(compile_commands, "w", encoding="utf-8") as file:
            json.dump(filtered_commands, file, indent=4)

        print(f"Successfully removed commands containing: {commands_to_remove}")
        print(f"Remaining entries: {len(filtered_commands)}")

    except Exception as e:
        print(f"Error while processing the file: {e}")





def main():
    if len(sys.argv) != 2:
        print("Usage: python script.py <path_to_compile_commands.json>")
        sys.exit(1)

    compile_commands = sys.argv[1]

    if not os.path.isfile(compile_commands):
        print(f"Error: The file '{compile_commands}' does not exist or is not a valid file.")
        sys.exit(1)

    remove_commands(compile_commands, ["ml64.exe"])

    # include_paths = [
    #     "C:\\\\develop\\\\jetbrainsruntime\\\\build\\\\windows-x86_64-server-release\\\\jdk\\\\include\\\\",
    # ]
    #
    # add_include_paths(compile_commands, include_paths)

if __name__ == "__main__":
    main()
