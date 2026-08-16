from dataclasses import dataclass


@dataclass
class Action:

    tool: str
    action: str
    data: dict